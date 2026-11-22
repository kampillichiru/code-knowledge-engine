package com.chiru.engine.sql.collect;

import com.chiru.engine.model.EntityId;
import com.chiru.engine.model.EntityType;
import com.chiru.engine.model.SourceLocation;
import com.chiru.engine.spoon.SpoonIds;
import com.chiru.engine.sql.analysis.Names;
import com.chiru.engine.sql.collect.SqlOrigin.Binding;
import com.chiru.engine.sql.collect.SqlOrigin.Consumer;
import com.chiru.engine.sql.collect.SqlOrigin.JavaUse;
import com.chiru.engine.sql.collect.SqlOrigin.ResultRead;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Finds SQL in Java string constants and concatenations, works out who executes it and, for JDBC statements held
 * in local variables, which {@code setXxx} calls bind its parameters and which {@code getXxx} calls read its result.
 * Parts that are not compile-time constants become {@code __DYN_n__} tokens and make the statement dynamic.
 * Strings handed to JPQL/HQL APIs are not SQL and are reported separately, never analysed as SQL.
 */
public final class JavaSqlCollector {

    /** @param configUses for SQL kept outside Java: statement key to the places that look it up and execute it. */
    public record Result(List<SqlOrigin> origins, List<SchemaDef> schemas, List<String> jpqlSkipped,
                         int sqlLikeIgnored, Map<String, List<Consumer>> configUses) {}

    private record Evaluated(String text, List<String> dynamicParts) {}

    private record Use(CtAbstractInvocation<?> call) {}

    private static final Pattern KEYWORD_START =
            Pattern.compile("(?is)^\\s*(select|insert|update|delete|merge|with|call|exec|execute)\\b.*");
    private static final List<Pattern> SQL_SHAPES = List.of(
            Pattern.compile("(?is)^\\s*select\\b.*\\bfrom\\b.*"),
            Pattern.compile("(?is)^\\s*insert\\s+(into|ignore\\s+into|overwrite)\\b.*"),
            Pattern.compile("(?is)^\\s*update\\s+\\S+(\\s+(as\\s+)?\\w+)?\\s+set\\b.*"),
            Pattern.compile("(?is)^\\s*delete\\s+(from\\s+|\\w+\\s+from\\s+).*"),
            Pattern.compile("(?is)^\\s*merge\\s+into\\b.*"),
            Pattern.compile("(?is)^\\s*with\\b.*\\bselect\\b.*"),
            Pattern.compile("(?is)^\\s*(call|exec|execute)\\s+[\\w.$#]+.*"),
            Pattern.compile("(?is)^\\s*\\{\\s*(\\?\\s*=\\s*)?call\\s+.*}\\s*$"));
    private static final Pattern RESULT_GETTER = Pattern.compile("get[A-Z]\\w*");
    private static final int TEXT_LIMIT = 300;

    private final CtModel model;
    private final Map<Path, String> displayPaths;
    private final Predicate<EntityId> exists;
    private final Map<String, List<SqlOrigin>> aliases;

    public JavaSqlCollector(CtModel model, Map<Path, String> displayPaths, Predicate<EntityId> exists) {
        this(model, displayPaths, exists, Map.of());
    }

    /** {@code aliases}: the keys under which SQL kept in XML, properties or YAML can be looked up from Java. */
    public JavaSqlCollector(CtModel model, Map<Path, String> displayPaths, Predicate<EntityId> exists,
                            Map<String, List<SqlOrigin>> aliases) {
        this.model = model;
        this.displayPaths = displayPaths;
        this.exists = exists;
        this.aliases = aliases;
    }

    public Result collect() {
        List<SqlOrigin> origins = new ArrayList<>();
        List<String> jpql = new ArrayList<>();
        int ignored = 0;
        for (CtExpression<?> root : stringRoots()) {
            Evaluated ev = evaluate(root);
            boolean sqlLike = SQL_SHAPES.stream().anyMatch(p -> p.matcher(ev.text()).matches());
            if (!sqlLike) {
                if (KEYWORD_START.matcher(ev.text()).matches()) {
                    ignored++;
                }
                continue;
            }
            CtElement parent = root.isParentInitialized() ? root.getParent() : null;
            SourceLocation loc = SpoonIds.locationOf(root, displayPaths);
            if (loc == null) {
                continue;
            }
            List<Consumer> consumers = new ArrayList<>();
            EntityId definedIn = null;
            if (parent instanceof CtAnnotation<?> ann) {
                Optional<Consumer> c = annotationConsumer(ann, loc);
                if (c.isEmpty()) {
                    if (ann.getAnnotationType().getSimpleName().endsWith("Query")) {
                        jpql.add(loc.toString());
                    }
                    continue;
                }
                consumers.add(c.get());
            } else {
                List<Use> uses = usesOf(root);
                if (uses.stream().anyMatch(u -> "jpql".equals(apiOf(u.call())))) {
                    jpql.add(loc.toString());
                    continue;
                }
                uses.forEach(u -> consumers.add(consumerOf(u.call())));
                if (parent instanceof CtField<?> f && f.getDeclaringType() != null) {
                    EntityId fieldId = EntityId.of(EntityType.FIELD, f.getDeclaringType().getQualifiedName() + "#" + f.getSimpleName());
                    definedIn = exists.test(fieldId) ? fieldId : null;
                }
            }
            SourcePosition pos = root.getPosition();
            List<String> notes = new ArrayList<>();
            for (int i = 0; i < ev.dynamicParts().size(); i++) {
                notes.add("dynamic part " + (i + 1) + " (__DYN_" + (i + 1) + "__): " + ev.dynamicParts().get(i));
            }
            origins.add(new SqlOrigin(SqlOrigin.Kind.JAVA, "java/" + loc.file() + ":" + pos.getLine() + ":" + pos.getColumn(),
                    loc, ev.text().replaceAll("\\s+", " ").trim(), !ev.dynamicParts().isEmpty(), notes, List.of(),
                    new JavaUse(definedIn, consumers)));
        }
        return new Result(origins, ormSchemas(), jpql, ignored, linkConfigSql());
    }

    // ------------------------------------------------------------------ SQL kept outside Java

    private static final Pattern LOOKUP_NAME = Pattern.compile("(?i)(get|load|read|lookup|resolve|fetch|find|sql)\\w*");

    /**
     * Connects Java to SQL configured in XML, properties or YAML: a string literal equal to a configured key
     * ({@code props.getProperty("stmt.select")}, {@code sqlMap.queryForList("getRows", p)}), and calls to a MyBatis
     * mapper interface method whose namespace and id match a mapper statement.
     */
    private Map<String, List<Consumer>> linkConfigSql() {
        Map<String, Set<Consumer>> found = new LinkedHashMap<>();
        if (aliases.isEmpty()) {
            return Map.of();
        }
        for (CtLiteral<?> lit : this.<CtLiteral<?>>all(CtLiteral.class)) {
            if (!(lit.getValue() instanceof String s)) {
                continue;
            }
            List<SqlOrigin> hit = aliases.get(s.trim());
            if (hit == null || hit.size() != 1) {
                continue;
            }
            List<Consumer> cs = consumersForKey(lit, s.trim(), 0);
            if (!cs.isEmpty()) {
                found.computeIfAbsent(hit.get(0).key(), k -> new LinkedHashSet<>()).addAll(cs);
            }
        }
        for (CtInvocation<?> inv : this.<CtInvocation<?>>all(CtInvocation.class)) {
            CtExecutableReference<?> ref = inv.getExecutable();
            if (ref == null || ref.getDeclaringType() == null) {
                continue;
            }
            List<SqlOrigin> hit = aliases.get(ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName());
            if (hit != null && hit.size() == 1) {
                CtTypeMember member = SpoonIds.enclosingMember(inv);
                found.computeIfAbsent(hit.get(0).key(), k -> new LinkedHashSet<>()).add(new Consumer(
                        member == null ? null : SpoonIds.methodId(member), "mapper-interface", SpoonIds.locationOf(inv, displayPaths),
                        false, List.of(), List.of(), "MyBatis mapper method: parameters are bound by the framework from the call arguments"));
            }
        }
        Map<String, List<Consumer>> out = new LinkedHashMap<>();
        found.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    private List<Consumer> consumersForKey(CtExpression<?> expr, String key, int depth) {
        List<Consumer> out = new ArrayList<>();
        for (Use u : usesOf(expr)) {
            CtAbstractInvocation<?> inv = u.call();
            String api = apiOf(inv);
            String name = inv.getExecutable().getSimpleName();
            boolean lookup = api.equals("unknown") && depth < 3 && inv instanceof CtExpression<?> e
                    && LOOKUP_NAME.matcher(name).matches() && returnsString(e) && !usesOf(e).isEmpty();
            if (lookup) {
                out.addAll(consumersForKey((CtExpression<?>) inv, key, depth + 1));
                continue;
            }
            Consumer c = consumerOf(inv);
            if (c.api().equals("unknown")) {
                c = new Consumer(c.method(), "named-statement", c.location(), false, List.of(), List.of(),
                        "SQL selected by the key \"" + key + "\" passed to " + name + "(); parameters are not derived");
            }
            out.add(c);
        }
        return out;
    }

    private static boolean returnsString(CtExpression<?> e) {
        return e.getType() == null || e.getType().getQualifiedName().equals("java.lang.String")
                || e.getType().getQualifiedName().equals("java.lang.Object");
    }

    // ------------------------------------------------------------------ finding and evaluating string expressions

    private List<CtExpression<?>> stringRoots() {
        List<CtExpression<?>> roots = new ArrayList<>();
        Set<CtElement> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CtLiteral<?> lit : this.<CtLiteral<?>>all(CtLiteral.class)) {
            if (!(lit.getValue() instanceof String)) {
                continue;
            }
            CtElement top = lit;
            while (top.isParentInitialized() && top.getParent() instanceof CtBinaryOperator<?> b
                    && b.getKind() == BinaryOperatorKind.PLUS) {
                top = b;
            }
            if (seen.add(top)) {
                roots.add((CtExpression<?>) top);
            }
        }
        return roots;
    }

    private Evaluated evaluate(CtExpression<?> e) {
        StringBuilder sb = new StringBuilder();
        List<String> dyn = new ArrayList<>();
        eval(e, sb, dyn, 0);
        return new Evaluated(sb.toString(), dyn);
    }

    private void eval(CtExpression<?> e, StringBuilder sb, List<String> dyn, int depth) {
        if (e instanceof CtLiteral<?> l) {
            sb.append(l.getValue());
            return;
        }
        if (e instanceof CtBinaryOperator<?> b && b.getKind() == BinaryOperatorKind.PLUS) {
            eval(b.getLeftHandOperand(), sb, dyn, depth);
            eval(b.getRightHandOperand(), sb, dyn, depth);
            return;
        }
        if (depth < 6 && e instanceof CtFieldRead<?> fr && fr.getVariable() != null) {
            CtField<?> f = fr.getVariable().getDeclaration();
            if (f != null && f.hasModifier(ModifierKind.FINAL) && f.getDefaultExpression() != null) {
                eval(f.getDefaultExpression(), sb, dyn, depth + 1);
                return;
            }
        }
        if (depth < 6 && e instanceof CtVariableRead<?> vr && !(e instanceof CtFieldRead<?>) && vr.getVariable() != null
                && vr.getVariable().getDeclaration() instanceof CtLocalVariable<?> lv
                && lv.hasModifier(ModifierKind.FINAL) && lv.getDefaultExpression() != null) {
            eval(lv.getDefaultExpression(), sb, dyn, depth + 1);
            return;
        }
        dyn.add(abbrev(e));
        sb.append("__DYN_").append(dyn.size()).append("__");
    }

    // ------------------------------------------------------------------ who executes the SQL

    private List<Use> usesOf(CtExpression<?> root) {
        List<Use> uses = new ArrayList<>();
        if (!root.isParentInitialized()) {
            return uses;
        }
        CtElement parent = root.getParent();
        if (parent instanceof CtAbstractInvocation<?> inv && isArgument(inv, root)) {
            uses.add(new Use(inv));
        } else if (parent instanceof CtField<?> f && f.getDefaultExpression() == root) {
            for (CtFieldRead<?> read : this.<CtFieldRead<?>>all(CtFieldRead.class)) {
                if (read.getVariable() != null && read.getVariable().getDeclaration() == f) {
                    invocationTaking(read).ifPresent(uses::add);
                }
            }
        } else if (parent instanceof CtLocalVariable<?> lv && lv.getDefaultExpression() == root) {
            for (CtVariableRead<?> read : this.<CtVariableRead<?>>all(CtVariableRead.class)) {
                if (!(read instanceof CtFieldRead<?>) && read.getVariable() != null && read.getVariable().getDeclaration() == lv) {
                    invocationTaking(read).ifPresent(uses::add);
                }
            }
        }
        return uses;
    }

    private static Optional<Use> invocationTaking(CtExpression<?> read) {
        if (read.isParentInitialized() && read.getParent() instanceof CtAbstractInvocation<?> inv && isArgument(inv, read)) {
            return Optional.of(new Use(inv));
        }
        return Optional.empty();
    }

    private static boolean isArgument(CtAbstractInvocation<?> inv, CtExpression<?> e) {
        return inv.getArguments().stream().anyMatch(a -> a == e);
    }

    private static String apiOf(CtAbstractInvocation<?> inv) {
        return switch (inv.getExecutable().getSimpleName()) {
            case "prepareStatement", "prepareCall" -> "jdbc-prepare";
            case "executeQuery", "executeUpdate", "executeLargeUpdate", "execute", "addBatch" -> "jdbc-statement";
            case "createNativeQuery" -> "jpa-native";
            case "createQuery", "createNamedQuery", "createTypedQuery" -> "jpql";
            case "selectList", "selectOne", "selectMap", "queryForPaginatedList" -> "mapped-statement";
            case "query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet", "queryForStream",
                 "update", "batchUpdate", "call" -> "jdbc-template";
            default -> "unknown";
        };
    }

    private Consumer consumerOf(CtAbstractInvocation<?> inv) {
        String api = apiOf(inv);
        CtTypeMember member = SpoonIds.enclosingMember(inv);
        EntityId method = member == null ? null : SpoonIds.methodId(member);
        SourceLocation loc = SpoonIds.locationOf(inv, displayPaths);
        if (api.equals("jdbc-prepare") && member != null) {
            CtVariable<?> ps = resultVariable(inv);
            if (ps == null) {
                return new Consumer(method, api, loc, false, List.of(), List.of(),
                        "the prepared statement is not held in a local variable, so parameter bindings were not derived");
            }
            List<Binding> bindings = bindingsOf(ps, (CtElement) member);
            List<ResultRead> reads = new ArrayList<>();
            for (CtInvocation<?> call : this.<CtInvocation<?>>within((CtElement) member, CtInvocation.class)) {
                if (isCallOn(call, ps) && call.getExecutable().getSimpleName().equals("executeQuery")) {
                    CtVariable<?> rs = resultVariable(call);
                    if (rs != null) {
                        reads.addAll(readsOf(rs, (CtElement) member));
                    }
                }
            }
            return new Consumer(method, api, loc, true, bindings, reads, null);
        }
        if (api.equals("jdbc-statement") && member != null) {
            CtVariable<?> rs = inv.getExecutable().getSimpleName().equals("executeQuery") ? resultVariable(inv) : null;
            List<ResultRead> reads = rs == null ? List.of() : readsOf(rs, (CtElement) member);
            return new Consumer(method, api, loc, true, List.of(), reads, null);
        }
        String note = switch (api) {
            case "jdbc-template" -> "parameters are bound by the framework from call arguments; bindings were not derived";
            case "jpa-native" -> "JPA native query parameters were not derived";
            case "unknown" -> "passed to " + inv.getExecutable().getSimpleName() + "(), which is not a recognised SQL API";
            default -> "bindings were not derived";
        };
        return new Consumer(method, api, loc, false, List.of(), List.of(), note);
    }

    private Optional<Consumer> annotationConsumer(CtAnnotation<?> ann, SourceLocation loc) {
        String name = ann.getAnnotationType().getSimpleName();
        boolean nativeQuery = name.equals("NamedNativeQuery") || (name.equals("Query") && isTrue(ann.getValue("nativeQuery")));
        if (!nativeQuery) {
            return Optional.empty();
        }
        CtElement annotated = ann.getAnnotatedElement();
        EntityId method = annotated instanceof CtMethod<?> m && m.getDeclaringType() != null ? SpoonIds.methodId(m) : null;
        return Optional.of(new Consumer(method, "@" + name + "(native)", loc, false, List.of(), List.of(),
                "annotation-based native query: parameters are bound by the framework from the method signature"));
    }

    private static boolean isTrue(CtExpression<?> e) {
        return e instanceof CtLiteral<?> l && Boolean.TRUE.equals(l.getValue());
    }

    // ------------------------------------------------------------------ JDBC statement and result tracking

    /** The local variable that receives the value of {@code inv}, if any. */
    private static CtVariable<?> resultVariable(CtAbstractInvocation<?> inv) {
        CtElement p = ((CtElement) inv).isParentInitialized() ? ((CtElement) inv).getParent() : null;
        if (p instanceof CtLocalVariable<?> lv && lv.getDefaultExpression() == inv) {
            return lv;
        }
        if (p instanceof CtAssignment<?, ?> a && a.getAssignment() == inv && a.getAssigned() instanceof CtVariableWrite<?> w
                && w.getVariable() != null && !(w instanceof CtFieldWrite<?>)) {
            return w.getVariable().getDeclaration();
        }
        return null;
    }

    private static boolean isCallOn(CtInvocation<?> call, CtVariable<?> var) {
        return call.getTarget() instanceof CtVariableRead<?> r && r.getVariable() != null
                && r.getVariable().getDeclaration() == var;
    }

    private List<Binding> bindingsOf(CtVariable<?> ps, CtElement member) {
        List<Binding> bindings = new ArrayList<>();
        for (CtInvocation<?> call : this.<CtInvocation<?>>within(member, CtInvocation.class)) {
            String name = call.getExecutable().getSimpleName();
            if (!isCallOn(call, ps) || !name.startsWith("set") || call.getArguments().size() < 2) {
                continue;
            }
            CtExpression<?> value = call.getArguments().get(1);
            bindings.add(new Binding(constantInt(call.getArguments().get(0)), name, abbrev(value), variablesIn(value),
                    SpoonIds.locationOf(call, displayPaths)));
        }
        return bindings;
    }

    private List<ResultRead> readsOf(CtVariable<?> rs, CtElement member) {
        List<ResultRead> reads = new ArrayList<>();
        for (CtInvocation<?> call : this.<CtInvocation<?>>within(member, CtInvocation.class)) {
            String name = call.getExecutable().getSimpleName();
            if (!isCallOn(call, rs) || !RESULT_GETTER.matcher(name).matches() || call.getArguments().isEmpty()) {
                continue;
            }
            CtExpression<?> key = call.getArguments().get(0);
            String label = key instanceof CtLiteral<?> l && l.getValue() instanceof String s ? Names.norm(s) : null;
            int index = key instanceof CtLiteral<?> l2 && l2.getValue() instanceof Integer i ? i : -1;
            if (label == null && index < 0) {
                continue;
            }
            CtElement top = call;
            while (top.isParentInitialized() && top.getParent() instanceof CtInvocation<?> up && up.getTarget() == top) {
                top = up;
            }
            reads.add(new ResultRead(label, index, name, abbrev(top), flowOf(top),
                    SpoonIds.locationOf(call, displayPaths)));
        }
        return reads;
    }

    /** Where the value goes next: an argument of a call or constructor, or a local variable. */
    private String flowOf(CtElement value) {
        if (!value.isParentInitialized()) {
            return null;
        }
        CtElement p = value.getParent();
        if (p instanceof CtAbstractInvocation<?> inv) {
            for (int i = 0; i < inv.getArguments().size(); i++) {
                if (inv.getArguments().get(i) == value) {
                    return calleeId(inv) + "#arg" + (i + 1);
                }
            }
        }
        if (p instanceof CtLocalVariable<?> lv) {
            return "local:" + lv.getSimpleName();
        }
        return null;
    }

    private static String calleeId(CtAbstractInvocation<?> inv) {
        CtExecutable<?> decl = inv.getExecutable().getDeclaration();
        if (decl instanceof CtMethod<?> || decl instanceof CtConstructor<?>) {
            return SpoonIds.methodId((CtTypeMember) decl).value();
        }
        CtExecutableReference<?> ref = inv.getExecutable();
        String owner = ref.getDeclaringType() == null ? "?" : ref.getDeclaringType().getQualifiedName();
        return "method:" + owner + "#" + ref.getSimpleName() + "("
                + ref.getParameters().stream().map(SpoonIds::typeName).collect(Collectors.joining(",")) + ")";
    }

    private static int constantInt(CtExpression<?> e) {
        return e instanceof CtLiteral<?> l && l.getValue() instanceof Integer i ? i : -1;
    }

    private static List<String> variablesIn(CtExpression<?> e) {
        Set<String> names = new LinkedHashSet<>();
        for (CtVariableRead<?> r : e.<CtVariableRead<?>>getElements(new TypeFilter<>(CtVariableRead.class))) {
            names.add(r.getVariable().getSimpleName());
        }
        return List.copyOf(names);
    }

    // ------------------------------------------------------------------ ORM mappings as schema evidence

    private List<SchemaDef> ormSchemas() {
        List<SchemaDef> schemas = new ArrayList<>();
        for (CtClass<?> c : this.<CtClass<?>>all(CtClass.class)) {
            CtAnnotation<?> entity = annotation(c, "Entity");
            CtAnnotation<?> table = annotation(c, "Table");
            if (entity == null && table == null) {
                continue;
            }
            String name = table == null ? null : stringValue(table, "name");
            boolean inferred = name == null || name.isBlank();
            if (inferred) {
                String entityName = entity == null ? null : stringValue(entity, "name");
                name = entityName != null && !entityName.isBlank() ? entityName : c.getSimpleName();
            }
            SourceLocation loc = SpoonIds.locationOf(c, displayPaths);
            List<SchemaDef.Col> cols = new ArrayList<>();
            for (CtField<?> f : c.getFields()) {
                if (f.hasModifier(ModifierKind.STATIC) || f.hasModifier(ModifierKind.TRANSIENT)
                        || annotation(f, "Transient") != null || annotation(f, "OneToMany") != null
                        || annotation(f, "ManyToMany") != null || annotation(f, "ElementCollection") != null) {
                    continue;
                }
                CtAnnotation<?> col = annotation(f, "Column") != null ? annotation(f, "Column") : annotation(f, "JoinColumn");
                String colName = col == null ? null : stringValue(col, "name");
                boolean colInferred = colName == null || colName.isBlank();
                if (colInferred) {
                    if (annotation(f, "ManyToOne") != null || annotation(f, "OneToOne") != null) {
                        continue; // join column name would depend on the naming strategy
                    }
                    colName = f.getSimpleName();
                }
                cols.add(new SchemaDef.Col(colName, SpoonIds.typeName(f.getType()), "", colInferred,
                        SpoonIds.locationOf(f, displayPaths)));
            }
            schemas.add(new SchemaDef(name, "orm", c.getQualifiedName(), inferred, loc, cols));
        }
        return schemas;
    }

    private static CtAnnotation<?> annotation(CtElement e, String simpleName) {
        return e.getAnnotations().stream()
                .filter(a -> a.getAnnotationType().getSimpleName().equals(simpleName)).findFirst().orElse(null);
    }

    private String stringValue(CtAnnotation<?> ann, String key) {
        CtExpression<?> v = ann.getValue(key);
        if (v == null) {
            return null;
        }
        Evaluated ev = evaluate(v);
        return ev.dynamicParts().isEmpty() ? ev.text() : null;
    }

    // ------------------------------------------------------------------ helpers

    private static String abbrev(Object o) {
        String flat = String.valueOf(o).replaceAll("\\s+", " ").trim();
        return flat.length() <= TEXT_LIMIT ? flat : flat.substring(0, TEXT_LIMIT) + "...";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <E> List<E> all(Class<?> type) {
        return new ArrayList<>((List) model.getElements(new TypeFilter(type)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <E> List<E> within(CtElement scope, Class<?> type) {
        return new ArrayList<>((List) scope.getElements(new TypeFilter(type)));
    }
}

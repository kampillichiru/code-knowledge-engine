package com.chiru.engine.spoon;

import com.chiru.engine.model.*;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.AbstractFilter;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Turns a Spoon model into analysis-model facts. Every fact carries file and line evidence taken from
 * the parsed source. Call edges say how well the target is known: VERIFIED when the call site
 * statically binds to one analysed method, PARTIAL when the target may be overridden or lives outside the
 * analysed sources, UNRESOLVED when Spoon could not even identify the declaring type.
 */
final class SourceExtractor {

    private static final String EXTRACTOR = SpoonSourceStage.NAME;
    private static final Set<String> ENTRY_ANNOTATIONS = Set.of("RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping", "Scheduled", "KafkaListener", "JmsListener",
            "RabbitListener", "EventListener", "PostConstruct");
    private static final Set<String> STEREOTYPES = Set.of("Controller", "RestController", "Service", "Repository",
            "Component", "Configuration", "Entity", "SpringBootApplication", "Mapper");
    private static final int TEXT_LIMIT = 300;

    private final AnalysisModel model;
    private final Map<Path, String> displayPaths;
    private final Set<EntityId> overridden = new HashSet<>();
    private final Map<EntityId, List<EntityId>> overriders = new HashMap<>();
    private int outsideMember;
    private int noPosition;
    private int unresolvedCalls;
    private SourceLocation firstUnresolved;

    SourceExtractor(AnalysisModel model, Map<Path, String> displayPaths) {
        this.model = model;
        this.displayPaths = displayPaths;
    }

    void extract(CtModel ctModel) {
        List<CtType<?>> types = all(ctModel, CtType.class);
        types.removeIf(t -> t instanceof CtTypeParameter);
        types.forEach(this::declareType);

        List<CtTypeMember> members = new ArrayList<>();
        members.addAll(this.<CtMethod<?>>all(ctModel, CtMethod.class));
        members.addAll(this.<CtConstructor<?>>all(ctModel, CtConstructor.class));
        members.forEach(this::declareMethod);
        this.<CtField<?>>all(ctModel, CtField.class).forEach(this::declareField);

        computeOverrides(types);
        extractInvocations(ctModel);
        extractBranches(ctModel);
        new DataFlowExtractor(model, displayPaths).extract(ctModel);
        reportGaps();
    }

    // ---------------------------------------------------------------- types, methods, fields

    private EntityId declareType(CtType<?> type) {
        EntityId id = EntityId.of(EntityType.CLASS, type.getQualifiedName());
        Map<String, String> a = new LinkedHashMap<>();
        a.put("kind", kindOf(type));
        a.put("simpleName", type.getSimpleName());
        String pkg = type.getPackage() == null ? "" : type.getPackage().getQualifiedName();
        a.put("package", pkg);
        putIfNotEmpty(a, "modifiers", modifiers(type));
        putIfNotEmpty(a, "annotations", annotations(type));
        String stereotype = type.getAnnotations().stream().map(x -> x.getAnnotationType().getSimpleName())
                .filter(STEREOTYPES::contains).findFirst().orElse(null);
        if (stereotype != null) {
            a.put("stereotype", stereotype);
        }
        if (type.isAnonymous()) {
            a.put("anonymous", "true");
        }
        Evidence ev = evidence(type, type, "type declaration");
        model.putEntity(new Entity(id, type.getSimpleName(), a, List.of(ev)));

        EntityId pkgId = EntityId.of(EntityType.PACKAGE, pkg.isEmpty() ? "(default)" : pkg);
        model.putEntity(new Entity(pkgId, pkg.isEmpty() ? "(default)" : pkg, Map.of(), List.of(ev)));
        CtType<?> outer = type.getDeclaringType();
        EntityId container = outer == null ? pkgId : declareType(outer);
        link(container, RelationType.CONTAINS, id, TraceStatus.VERIFIED, ev);

        CtTypeReference<?> sup = type.getSuperclass();
        if (sup != null && !sup.isImplicit() && !"java.lang.Object".equals(sup.getQualifiedName())) {
            link(id, RelationType.EXTENDS, typeRefId(sup, type), TraceStatus.VERIFIED, ev);
        }
        for (CtTypeReference<?> i : type.getSuperInterfaces()) {
            if (!i.isImplicit()) {
                link(id, type.isInterface() ? RelationType.EXTENDS : RelationType.IMPLEMENTS,
                        typeRefId(i, type), TraceStatus.VERIFIED, ev);
            }
        }
        return id;
    }

    private EntityId typeRefId(CtTypeReference<?> ref, CtType<?> from) {
        CtType<?> decl = ref.getDeclaration();
        if (decl != null) {
            return declareType(decl);
        }
        EntityId id = EntityId.of(EntityType.CLASS, ref.getQualifiedName());
        model.putEntity(new Entity(id, ref.getSimpleName(), Map.of("external", "true"),
                List.of(evidence(from, from, "supertype outside the analysed sources"))));
        return id;
    }

    private void declareMethod(CtTypeMember m) {
        CtType<?> owner = m.getDeclaringType();
        EntityId ownerId = declareType(owner);
        EntityId id = methodId(m);
        boolean ctor = m instanceof CtConstructor<?>;
        Map<String, String> a = new LinkedHashMap<>();
        a.put("name", ctor ? "<init>" : m.getSimpleName());
        a.put("constructor", String.valueOf(ctor));
        List<CtParameter<?>> params = parameters(m);
        a.put("parameters", params.stream().map(p -> typeName(p.getType()) + " " + p.getSimpleName())
                .collect(Collectors.joining(", ")));
        if (m instanceof CtMethod<?> method) {
            a.put("returnType", typeName(method.getType()));
            if (isAbstract(method, owner)) {
                a.put("abstract", "true");
            }
        }
        putIfNotEmpty(a, "modifiers", modifiers(m));
        putIfNotEmpty(a, "annotations", annotations(m));
        if (m.isImplicit()) {
            a.put("implicit", "true");
        }
        String entry = entryPoint(m);
        if (entry != null) {
            a.put("entryPoint", entry);
        }
        Evidence ev = evidence(m, owner, m.isImplicit() ? "implicit member (location of owning type)" : "declaration");
        model.putEntity(new Entity(id, a.get("name"), a, List.of(ev)));
        link(ownerId, RelationType.CONTAINS, id, TraceStatus.VERIFIED, ev);
    }

    private void declareField(CtField<?> f) {
        CtType<?> owner = f.getDeclaringType();
        if (owner == null) {
            return;
        }
        EntityId ownerId = declareType(owner);
        EntityId id = EntityId.of(EntityType.FIELD, owner.getQualifiedName() + "#" + f.getSimpleName());
        Map<String, String> a = new LinkedHashMap<>();
        a.put("type", typeName(f.getType()));
        putIfNotEmpty(a, "modifiers", modifiers(f));
        if (f.getDefaultExpression() != null) {
            a.put("initializer", abbrev(f.getDefaultExpression().toString()));
        }
        Evidence ev = evidence(f, owner, "field declaration");
        model.putEntity(new Entity(id, f.getSimpleName(), a, List.of(ev)));
        link(ownerId, RelationType.CONTAINS, id, TraceStatus.VERIFIED, ev);
    }

    private static String entryPoint(CtTypeMember m) {
        if (m instanceof CtMethod<?> method && method.getSimpleName().equals("main")
                && method.hasModifier(ModifierKind.STATIC) && method.getParameters().size() == 1
                && method.getParameters().get(0).getType().getQualifiedName().equals("java.lang.String[]")) {
            return "main";
        }
        return m.getAnnotations().stream().map(x -> x.getAnnotationType().getSimpleName())
                .filter(ENTRY_ANNOTATIONS::contains).map(n -> "@" + n).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- overriding (for dispatch certainty)

    private void computeOverrides(List<CtType<?>> types) {
        for (CtType<?> type : types) {
            for (CtType<?> ancestor : ancestors(type)) {
                for (CtMethod<?> sub : type.getMethods()) {
                    for (CtMethod<?> sup : ancestor.getMethods()) {
                        if (!sup.hasModifier(ModifierKind.STATIC) && !sup.hasModifier(ModifierKind.PRIVATE)
                                && sub.isOverriding(sup)) {
                            overridden.add(methodId(sup));
                            overriders.computeIfAbsent(methodId(sup), k -> new ArrayList<>()).add(methodId(sub));
                        }
                    }
                }
            }
        }
    }

    private static Set<CtType<?>> ancestors(CtType<?> type) {
        Set<CtType<?>> result = new LinkedHashSet<>();
        Deque<CtType<?>> queue = new ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            CtType<?> t = queue.poll();
            List<CtTypeReference<?>> refs = new ArrayList<>(t.getSuperInterfaces());
            if (t.getSuperclass() != null) {
                refs.add(t.getSuperclass());
            }
            for (CtTypeReference<?> r : refs) {
                CtType<?> decl = r.getDeclaration();
                if (decl != null && result.add(decl)) {
                    queue.add(decl);
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- invocations

    private void extractInvocations(CtModel ctModel) {
        for (CtAbstractInvocation<?> inv : this.<CtAbstractInvocation<?>>all(ctModel, CtAbstractInvocation.class)) {
            if (inv.isImplicit() || inv.getExecutable() == null) {
                continue;
            }
            CtTypeMember member = enclosingMember(inv);
            SourcePosition pos = inv.getPosition();
            if (member == null) {
                outsideMember++;
                continue;
            }
            if (pos == null || !pos.isValidPosition()) {
                noPosition++;
                continue;
            }
            EntityId caller = methodId(member);
            Callee callee = resolveCallee(inv, inv.getExecutable());
            Evidence ev = evidence(inv, member, callee.note);

            EntityId invId = SpoonIds.invocationId(inv, member);
            Map<String, String> a = new LinkedHashMap<>();
            a.put("kind", inv instanceof CtConstructorCall<?> ? "new" : "call");
            a.put("callee", callee.id.value());
            a.put("text", abbrev(inv.toString()));
            model.putEntity(new Entity(invId, inv.getExecutable().getSimpleName(), a, List.of(ev)));
            link(caller, RelationType.CONTAINS, invId, TraceStatus.VERIFIED, ev);

            link(caller, RelationType.CALLS, callee.id, callee.status, ev);
            link(invId, RelationType.INVOKES, callee.id, callee.status, ev);
            for (EntityId possible : overriders.getOrDefault(callee.id, List.of())) {
                Evidence pev = evidence(inv, member, "possible runtime target via class hierarchy");
                link(caller, RelationType.CALLS, possible, TraceStatus.PARTIAL, pev);
                link(invId, RelationType.INVOKES, possible, TraceStatus.PARTIAL, pev);
            }
        }
    }

    private record Callee(EntityId id, TraceStatus status, String note) {}

    private Callee resolveCallee(CtAbstractInvocation<?> inv, CtExecutableReference<?> ref) {
        CtExecutable<?> decl = ref.getDeclaration();
        if (decl instanceof CtConstructor<?> c && model.entity(methodId(c)).isPresent()) {
            return new Callee(methodId(c), TraceStatus.VERIFIED, "constructor call");
        }
        if (decl instanceof CtMethod<?> m && model.entity(methodId(m)).isPresent()) {
            EntityId id = methodId(m);
            boolean fixed = m.hasModifier(ModifierKind.STATIC) || m.hasModifier(ModifierKind.PRIVATE);
            boolean dynamic = !fixed && (overridden.contains(id) || isAbstract(m, m.getDeclaringType()));
            return dynamic
                    ? new Callee(id, TraceStatus.PARTIAL, "dynamic dispatch: runtime target depends on the receiver type")
                    : new Callee(id, TraceStatus.VERIFIED, "statically bound call");
        }
        CtTypeReference<?> owner = ref.getDeclaringType();
        String ownerName = owner == null ? "?" : owner.getQualifiedName();
        String params = ref.getParameters().stream().map(SourceExtractor::typeName).collect(Collectors.joining(","));
        EntityId id = EntityId.of(EntityType.METHOD, ownerName + "#" + ref.getSimpleName() + "(" + params + ")");
        boolean unresolved = owner == null;
        String note = unresolved ? "call target could not be resolved" : "call to a method outside the analysed sources";
        Map<String, String> a = new LinkedHashMap<>();
        a.put("external", "true");
        a.put("owner", ownerName);
        model.putEntity(new Entity(id, ref.getSimpleName(), a,
                List.of(evidence(inv, inv, note))));
        if (unresolved) {
            unresolvedCalls++;
            if (firstUnresolved == null) {
                firstUnresolved = locationOf(inv);
            }
        }
        return new Callee(id, unresolved ? TraceStatus.UNRESOLVED : TraceStatus.PARTIAL, note);
    }

    // ---------------------------------------------------------------- branches

    private static String branchKind(CtElement e) {
        return SpoonBranches.branchKind(e);
    }

    private void extractBranches(CtModel ctModel) {
        List<CtElement> branches = ctModel.getElements(new AbstractFilter<CtElement>(CtElement.class) {
            @Override
            public boolean matches(CtElement e) {
                return branchKind(e) != null && !e.isImplicit();
            }
        });
        record Pending(CtElement element, EntityId id, EntityId memberId, Evidence evidence) {}
        List<Pending> pending = new ArrayList<>();
        for (CtElement e : branches) {
            CtTypeMember member = enclosingMember(e);
            String kind = branchKind(e);
            EntityId id = branchId(e, kind);
            if (member == null || id == null) {
                outsideMember += member == null ? 1 : 0;
                noPosition += member == null ? 0 : 1;
                continue;
            }
            Evidence ev = evidence(e, member, kind + " branch");
            SpoonBranches.Placement placement = placement(e, methodId(member));
            Map<String, String> a = new LinkedHashMap<>();
            a.put("kind", kind);
            CtElement condition = conditionOf(e);
            if (condition != null) {
                a.put("condition", abbrev(condition.toString()));
            }
            if (e instanceof CtConditional<?> c) {
                a.put("thenValue", abbrev(c.getThenExpression().toString()));
                a.put("elseValue", abbrev(c.getElseExpression().toString()));
            }
            if (e instanceof CtAbstractSwitch<?> sw) {
                a.put("cases", sw.getCases().stream().map(SourceExtractor::caseLabel).collect(Collectors.joining(", ")));
            }
            if (e instanceof CtTry t) {
                a.put("catches", t.getCatchers().stream()
                        .map(c -> typeName(c.getParameter().getType())).collect(Collectors.joining(", ")));
                a.put("finally", String.valueOf(t.getFinalizer() != null));
            }
            if (placement.arm() != null) {
                a.put("parentArm", placement.arm());
            }
            model.putEntity(new Entity(id, kind, a, List.of(ev)));
            pending.add(new Pending(e, id, methodId(member), ev));
        }
        for (Pending p : pending) {
            SpoonBranches.Placement placement = placement(p.element, p.memberId);
            link(placement.parent(), RelationType.CONTAINS, p.id, TraceStatus.VERIFIED, p.evidence);
        }
    }

    private static EntityId branchId(CtElement e, String kind) {
        return SpoonBranches.branchId(e, kind);
    }

    private static SpoonBranches.Placement placement(CtElement e, EntityId memberId) {
        return SpoonBranches.placement(e, memberId);
    }

    private static CtElement conditionOf(CtElement e) {
        return SpoonBranches.conditionOf(e);
    }

    private static String caseLabel(CtCase<?> c) {
        return SpoonBranches.caseLabel(c);
    }

    // ---------------------------------------------------------------- helpers

    private void reportGaps() {
        if (unresolvedCalls > 0) {
            warn(AnalysisWarning.Severity.WARNING, unresolvedCalls
                    + " call(s) could not be resolved to a declaring type (missing classpath); marked UNRESOLVED", firstUnresolved);
        }
        if (outsideMember > 0) {
            warn(AnalysisWarning.Severity.INFO, outsideMember
                    + " call(s)/branch(es) sit outside any method or constructor (field initializers, static blocks) "
                    + "and are not modelled yet", null);
        }
        if (noPosition > 0) {
            warn(AnalysisWarning.Severity.INFO, noPosition
                    + " element(s) had no source position and were skipped", null);
        }
    }

    private void warn(AnalysisWarning.Severity severity, String message, SourceLocation loc) {
        model.addWarning(new AnalysisWarning(EXTRACTOR, severity, message, loc));
    }

    private void link(EntityId from, RelationType type, EntityId to, TraceStatus status, Evidence ev) {
        model.addRelationship(new Relationship(from, type, to, status, List.of(ev)));
    }

    private static CtTypeMember enclosingMember(CtElement e) {
        return SpoonIds.enclosingMember(e);
    }

    private static List<CtParameter<?>> parameters(CtTypeMember m) {
        return SpoonIds.parameters(m);
    }

    private static String key(EntityId methodId) {
        return methodId.value().substring(methodId.value().indexOf(':') + 1);
    }

    private static EntityId methodId(CtTypeMember m) {
        return SpoonIds.methodId(m);
    }

    private static boolean isAbstract(CtMethod<?> m, CtType<?> owner) {
        return m.hasModifier(ModifierKind.ABSTRACT) || (owner.isInterface() && m.getBody() == null);
    }

    private static String typeName(CtTypeReference<?> ref) {
        return SpoonIds.typeName(ref);
    }

    private static String kindOf(CtType<?> type) {
        if (type instanceof CtAnnotationType<?>) return "annotation";
        if (type instanceof CtEnum<?>) return "enum";
        if (type instanceof CtRecord) return "record";
        if (type instanceof CtInterface<?>) return "interface";
        return "class";
    }

    /** Modifiers in the conventional Java order, independent of Spoon's set ordering. */
    private static String modifiers(CtModifiable m) {
        List<ModifierKind> order = List.of(ModifierKind.PUBLIC, ModifierKind.PROTECTED, ModifierKind.PRIVATE,
                ModifierKind.ABSTRACT, ModifierKind.STATIC, ModifierKind.FINAL, ModifierKind.TRANSIENT,
                ModifierKind.VOLATILE, ModifierKind.SYNCHRONIZED, ModifierKind.NATIVE, ModifierKind.STRICTFP);
        return m.getModifiers().stream()
                .sorted(Comparator.comparingInt(k -> order.contains(k) ? order.indexOf(k) : order.size()))
                .map(k -> k.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(" "));
    }

    private static String annotations(CtElement e) {
        return e.getAnnotations().stream().map(a -> "@" + a.getAnnotationType().getSimpleName())
                .collect(Collectors.joining(" "));
    }

    private static void putIfNotEmpty(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    private static String abbrev(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= TEXT_LIMIT ? flat : flat.substring(0, TEXT_LIMIT) + "...";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <E> List<E> all(CtModel ctModel, Class<?> type) {
        return new ArrayList<>((List) ctModel.getElements(new TypeFilter(type)));
    }

    private Evidence evidence(CtElement el, CtElement fallback, String note) {
        SourceLocation loc = locationOf(el);
        if (loc == null) {
            loc = locationOf(fallback);
        }
        return new Evidence(loc, EXTRACTOR, Certainty.EXTRACTED, note);
    }

    private SourceLocation locationOf(CtElement el) {
        return SpoonIds.locationOf(el, displayPaths);
    }
}

package com.chiru.engine.sql.analysis;

import com.chiru.engine.sql.analysis.SqlAnalysis.Access;
import com.chiru.engine.sql.analysis.SqlAnalysis.ColumnUse;
import com.chiru.engine.sql.analysis.SqlAnalysis.Param;
import com.chiru.engine.sql.analysis.SqlAnalysis.SelectItemInfo;
import com.chiru.engine.sql.analysis.SqlAnalysis.TableUse;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.conditional.XorExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

/**
 * Parses one SQL statement with JSqlParser and reports tables, columns (by access kind and role), joins,
 * CTEs, subqueries, parameters and output columns. A column is attributed to a table only when the SQL or the
 * {@link SchemaCatalog} establishes it; otherwise it is reported as unresolved with a reason.
 */
public final class SqlAnalyzer {

    private static final Pattern JDBC_CALL = Pattern.compile(
            "(?is)^\\s*\\{\\s*(\\?\\s*=\\s*)?call\\s+([\\w.$#]+)\\s*(\\((.*)\\))?\\s*}\\s*;?\\s*$");
    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "stddev", "variance",
            "listagg", "string_agg", "group_concat", "array_agg");
    private static final int TEXT_LIMIT = 300;

    public record ColumnDef(String name, String type, String specs) {}

    public record TableDef(String table, List<ColumnDef> columns) {}

    private final SchemaCatalog catalog;

    public SqlAnalyzer(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    public SqlAnalysis analyze(String sql) throws JSQLParserException {
        Matcher call = JDBC_CALL.matcher(sql);
        if (call.matches()) {
            return jdbcCall(sql, call.group(2));
        }
        return new Run(catalog).analyze(CCJSqlParserUtil.parse(sql));
    }

    /** The table definition when {@code sql} is a CREATE TABLE, else empty. */
    public static Optional<TableDef> tableDefinition(String sql) throws JSQLParserException {
        if (!(CCJSqlParserUtil.parse(sql) instanceof CreateTable ct) || ct.getColumnDefinitions() == null) {
            return Optional.empty();
        }
        List<ColumnDef> cols = new ArrayList<>();
        for (ColumnDefinition cd : ct.getColumnDefinitions()) {
            String specs = cd.getColumnSpecs() == null ? "" : String.join(" ", cd.getColumnSpecs());
            cols.add(new ColumnDef(Names.norm(cd.getColumnName()),
                    cd.getColDataType() == null ? "" : cd.getColDataType().toString(), specs));
        }
        return Optional.of(new TableDef(qualified(ct.getTable().getFullyQualifiedName()), cols));
    }

    private static SqlAnalysis jdbcCall(String sql, String routine) {
        List<Param> params = new ArrayList<>();
        int n = 0;
        for (char c : sql.toCharArray()) {
            if (c == '?') {
                params.add(new Param(++n, null, null, null, "procedure argument", false));
            }
        }
        return new SqlAnalysis("CALL", List.of(), List.of(), params, List.of(), List.of(), List.of(), List.of(), 0,
                List.of(), List.of(), List.of(Names.norm(routine)), List.of(), List.of(), List.of(), false, List.of());
    }

    // ------------------------------------------------------------------ names

    private static String qualified(String name) {
        return Arrays.stream(name.split("\\.")).map(Names::norm).collect(Collectors.joining("."));
    }

    private static String tableName(Table t) {
        return qualified(t.getFullyQualifiedName());
    }

    private static String simple(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    private static String abbrev(Object o) {
        String flat = String.valueOf(o).replaceAll("\\s+", " ").trim();
        return flat.length() <= TEXT_LIMIT ? flat : flat.substring(0, TEXT_LIMIT) + "...";
    }

    // ------------------------------------------------------------------ one analysis run

    private enum Kind { TABLE, DERIVED, CTE }

    /** A named row source in a FROM clause. {@code labels} are the known output names (null = unknown). */
    private record Source(Kind kind, String alias, String table, List<String> labels) {}

    private static final class Scope {
        final Scope parent;
        final List<Source> sources = new ArrayList<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        Source find(String qualifier) {
            for (Scope s = this; s != null; s = s.parent) {
                for (Source src : s.sources) {
                    if (qualifier.equals(src.alias()) || (src.kind() == Kind.TABLE && qualifier.equals(src.table()))) {
                        return src;
                    }
                }
            }
            return null;
        }
    }

    /** The column a parameter is compared with or assigned to, and whether the parameter is the whole operand. */
    private record Ctx(String column, String operator, boolean direct) {
        Ctx indirect() {
            return new Ctx(column, operator, false);
        }
    }

    private static final class Run {
        final SchemaCatalog catalog;
        final List<TableUse> tables = new ArrayList<>();
        final List<ColumnUse> columns = new ArrayList<>();
        final List<Param> params = new ArrayList<>();
        final List<SelectItemInfo> selectItems = new ArrayList<>();
        final List<String> insertColumns = new ArrayList<>();
        final List<String> joins = new ArrayList<>();
        final List<String> ctes = new ArrayList<>();
        final Set<String> functions = new LinkedHashSet<>();
        final Set<String> aggregates = new LinkedHashSet<>();
        final List<String> routines = new ArrayList<>();
        final List<String> groupBy = new ArrayList<>();
        final List<String> orderBy = new ArrayList<>();
        final List<String> predicates = new ArrayList<>();
        final Set<String> unsupported = new LinkedHashSet<>();
        final Map<String, List<String>> cteLabels = new HashMap<>();
        final Set<Object> withHandled = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<String, Integer> namedParams = new LinkedHashMap<>();
        int subqueries;
        int fallbackIndex;
        boolean dynamic;

        Run(SchemaCatalog catalog) {
            this.catalog = catalog;
        }

        SqlAnalysis analyze(Statement st) {
            String type;
            if (st instanceof Select s) {
                type = "SELECT";
                analyzeSelect(s, null, true);
            } else if (st instanceof Insert i) {
                type = "INSERT";
                insert(i);
            } else if (st instanceof Update u) {
                type = "UPDATE";
                update(u);
            } else if (st instanceof Delete d) {
                type = "DELETE";
                delete(d);
            } else if (st instanceof Execute e) {
                type = "CALL";
                execute(e);
            } else if (st instanceof CreateTable) {
                type = "CREATE_TABLE";
            } else {
                type = st.getClass().getSimpleName().toUpperCase(Locale.ROOT);
                unsupported.add("statement type " + st.getClass().getSimpleName());
            }
            return new SqlAnalysis(type, tables, columns, params, selectItems, insertColumns, joins, ctes, subqueries,
                    List.copyOf(functions), List.copyOf(aggregates), routines, groupBy, orderBy, predicates, dynamic,
                    List.copyOf(unsupported));
        }

        // -------------------------------------------------------------- SELECT

        List<String> analyzeSelect(Select s, Scope parent, boolean capture) {
            handleWith(s.getWithItemsList());
            if (s instanceof ParenthesedSelect p) {
                return analyzeSelect(p.getSelect(), parent, capture);
            }
            if (s instanceof PlainSelect ps) {
                return plain(ps, parent, capture);
            }
            if (s instanceof SetOperationList sol) {
                List<String> first = null;
                for (Select part : sol.getSelects()) {
                    List<String> labels = analyzeSelect(part, parent, capture && first == null);
                    if (first == null) {
                        first = labels;
                    }
                }
                if (sol.getOrderByElements() != null) {
                    sol.getOrderByElements().forEach(o -> orderBy.add(abbrev(o)));
                }
                return first == null ? List.of() : first;
            }
            if (s instanceof Values) {
                return List.of();
            }
            unsupported.add("select form " + s.getClass().getSimpleName());
            return List.of();
        }

        List<String> plain(PlainSelect ps, Scope parent, boolean capture) {
            Scope scope = new Scope(parent);
            addFromItem(scope, ps.getFromItem());
            addJoins(scope, ps.getJoins());

            List<String> labels = new ArrayList<>();
            List<SelectItemInfo> items = new ArrayList<>();
            for (SelectItem<?> item : ps.getSelectItems()) {
                selectItem(item, scope, labels, items);
            }
            if (capture) {
                selectItems.addAll(items);
            }
            if (ps.getWhere() != null) {
                predicates.add("WHERE " + abbrev(ps.getWhere()));
                walk(ps.getWhere(), scope, "where", null);
            }
            if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressionList() != null) {
                for (Object o : ps.getGroupBy().getGroupByExpressionList()) {
                    groupBy.add(abbrev(o));
                    walk((Expression) o, scope, "group by", null);
                }
            }
            if (ps.getHaving() != null) {
                predicates.add("HAVING " + abbrev(ps.getHaving()));
                walk(ps.getHaving(), scope, "having", null);
            }
            if (ps.getOrderByElements() != null) {
                for (OrderByElement ob : ps.getOrderByElements()) {
                    orderBy.add(abbrev(ob));
                    Expression ex = ob.getExpression();
                    boolean outputName = ex instanceof Column c && c.getTable() == null
                            && labels.contains(Names.norm(c.getColumnName()));
                    if (!outputName) {
                        walk(ex, scope, "order by", null);
                    }
                }
            }
            return labels;
        }

        void selectItem(SelectItem<?> item, Scope scope, List<String> labels, List<SelectItemInfo> items) {
            Expression e = item.getExpression();
            if (e instanceof AllTableColumns atc) {
                Source src = scope.find(Names.norm(atc.getTable().getName()));
                if (src == null) {
                    unsupported.add("'" + atc + "': qualifier not found in scope");
                    items.add(new SelectItemInfo("*", e.toString(), List.of(), true, "qualifier not found"));
                } else {
                    expandStar(src, labels, items);
                }
                return;
            }
            if (e instanceof AllColumns) {
                for (Source src : scope.sources) {
                    expandStar(src, labels, items);
                }
                return;
            }
            int before = columns.size();
            walk(e, scope, "select list", null);
            List<String> sources = columns.subList(before, columns.size()).stream()
                    .filter(c -> c.resolved() && c.access() == Access.READ && c.role().startsWith("select list"))
                    .map(c -> c.table() + "." + c.column()).distinct().toList();
            String label = item.getAlias() != null ? Names.norm(item.getAlias().getName())
                    : e instanceof Column c ? Names.norm(c.getColumnName()) : abbrev(e).toLowerCase(Locale.ROOT);
            labels.add(label);
            items.add(new SelectItemInfo(label, abbrev(e), sources, false, null));
        }

        void expandStar(Source src, List<String> labels, List<SelectItemInfo> items) {
            if (src.kind() == Kind.TABLE && src.table() != null && catalog.knowsTable(src.table())) {
                for (String col : catalog.columns(src.table())) {
                    columns.add(new ColumnUse(src.table(), col, Access.READ, "select list (*)", null));
                    labels.add(col);
                    items.add(new SelectItemInfo(col, "*", List.of(src.table() + "." + col), false, "expanded from schema"));
                }
            } else if (src.kind() == Kind.TABLE) {
                String table = src.table();
                columns.add(new ColumnUse(table, "*", Access.READ, "select list (*)",
                        "all columns are read; table is not in the schema so they cannot be listed"));
                items.add(new SelectItemInfo("*", "*", table == null ? List.of() : List.of(table + ".*"), true,
                        "columns unknown (table not in schema)"));
            } else if (src.labels() != null) {
                for (String l : src.labels()) {
                    labels.add(l);
                    items.add(new SelectItemInfo(l, "*", List.of(), false, "via " + src.alias()));
                }
            } else {
                items.add(new SelectItemInfo("*", "*", List.of(), true, "columns of " + src.alias() + " unknown"));
            }
        }

        void addFromItem(Scope scope, FromItem fi) {
            if (fi == null) {
                return;
            }
            if (fi instanceof Table t) {
                String name = tableName(t);
                String alias = t.getAlias() == null ? null : Names.norm(t.getAlias().getName());
                if (Names.isDynamic(simple(name))) {
                    dynamic = true;
                    scope.sources.add(new Source(Kind.TABLE, alias != null ? alias : name, null, null));
                } else if (!name.contains(".") && cteLabels.containsKey(name)) {
                    scope.sources.add(new Source(Kind.CTE, alias != null ? alias : name, null, cteLabels.get(name)));
                } else {
                    scope.sources.add(new Source(Kind.TABLE, alias != null ? alias : simple(name), name, null));
                    tables.add(new TableUse(name, alias, Access.READ));
                }
            } else if (fi instanceof ParenthesedSelect ps) {
                subqueries++;
                List<String> labels = analyzeSelect(ps.getSelect(), scope.parent, false);
                String alias = ps.getAlias() == null ? "(subquery " + subqueries + ")" : Names.norm(ps.getAlias().getName());
                scope.sources.add(new Source(Kind.DERIVED, alias, null, labels));
            } else if (fi instanceof ParenthesedFromItem pfi) {
                addFromItem(scope, pfi.getFromItem());
                addJoins(scope, pfi.getJoins());
            } else {
                unsupported.add("FROM item " + fi.getClass().getSimpleName());
            }
        }

        void addJoins(Scope scope, List<Join> list) {
            if (list == null) {
                return;
            }
            for (Join j : list) {
                addFromItem(scope, j.getRightItem());
                String kind = j.isSimple() ? "," : (j.isNatural() ? "NATURAL " : "")
                        + (j.isLeft() ? "LEFT " : j.isRight() ? "RIGHT " : j.isFull() ? "FULL " : j.isCross() ? "CROSS "
                        : j.isInner() ? "INNER " : "") + "JOIN";
                joins.add(abbrev(kind + " " + j.getRightItem()
                        + (j.getOnExpressions().isEmpty() ? "" : " ON " + j.getOnExpressions().stream()
                        .map(Object::toString).collect(Collectors.joining(" AND ")))));
                for (Expression on : j.getOnExpressions()) {
                    predicates.add("ON " + abbrev(on));
                    walk(on, scope, "join condition", null);
                }
                if (j.getUsingColumns() != null) {
                    for (Column using : j.getUsingColumns()) {
                        String name = Names.norm(using.getColumnName());
                        for (Source s : scope.sources) {
                            if (s.kind() == Kind.TABLE && s.table() != null
                                    && (!catalog.knowsTable(s.table()) || catalog.hasColumn(s.table(), name))) {
                                columns.add(new ColumnUse(s.table(), name, Access.READ, "join condition (USING)", null));
                            }
                        }
                    }
                }
            }
        }

        void handleWith(List<WithItem<?>> items) {
            if (items == null) {
                return;
            }
            for (WithItem<?> w : items) {
                if (!withHandled.add(w)) {
                    continue;
                }
                String name = Names.norm(w.getAliasName());
                ctes.add(name);
                cteLabels.put(name, null); // visible to a recursive reference inside its own body
                List<String> labels = w.getSelect() == null ? null : analyzeSelect(w.getSelect(), null, false);
                if (w.getWithItemList() != null && !w.getWithItemList().isEmpty()) {
                    labels = w.getWithItemList().stream().map(si -> Names.norm(si.getExpression().toString())).toList();
                }
                cteLabels.put(name, labels);
            }
        }

        // -------------------------------------------------------------- INSERT / UPDATE / DELETE / CALL

        void insert(Insert i) {
            handleWith(i.getWithItemsList());
            String table = tableName(i.getTable());
            tables.add(new TableUse(table, null, Access.INSERT));
            List<String> cols = new ArrayList<>();
            if (i.getColumns() != null && !i.getColumns().isEmpty()) {
                for (Column c : i.getColumns()) {
                    cols.add(Names.norm(c.getColumnName()));
                }
                cols.forEach(c -> columns.add(new ColumnUse(table, c, Access.INSERT, "insert column", null)));
            } else if (catalog.knowsTable(table)) {
                cols.addAll(catalog.columns(table));
                cols.forEach(c -> columns.add(new ColumnUse(table, c, Access.INSERT, "insert column",
                        "column list taken from schema (positional)")));
            } else {
                unsupported.add("INSERT into '" + table + "' without a column list and without schema: target columns unknown");
            }
            cols.forEach(c -> insertColumns.add(table + "." + c));

            Select sel = i.getSelect();
            Values vals = sel instanceof Values v ? v : null;
            if (vals != null) {
                insertValues(vals, table, cols);
            } else if (sel != null) {
                analyzeSelect(sel, null, true);
            } else if (i.isUseSet()) {
                unsupported.add("INSERT ... SET form");
            }
        }

        void insertValues(Values vals, String table, List<String> cols) {
            List<List<Expression>> rows = new ArrayList<>();
            ExpressionList<Expression> exprs = vals.getExpressions();
            if (exprs.stream().allMatch(e -> e instanceof ExpressionList<?>)) {
                for (Expression e : exprs) {
                    rows.add(new ArrayList<>((ExpressionList<?>) e));
                }
            } else {
                rows.add(new ArrayList<>(exprs));
            }
            Scope empty = new Scope(null);
            for (List<Expression> row : rows) {
                if (!cols.isEmpty() && row.size() != cols.size()) {
                    unsupported.add("INSERT value count " + row.size() + " differs from column count " + cols.size());
                }
                for (int j = 0; j < row.size(); j++) {
                    Expression v = row.get(j);
                    Ctx ctx = j < cols.size() && row.size() == cols.size()
                            ? new Ctx(table + "." + cols.get(j), "=", v instanceof JdbcParameter) : null;
                    walk(v, empty, "insert value", ctx);
                }
            }
        }

        void update(Update u) {
            handleWith(u.getWithItemsList());
            Scope scope = new Scope(null);
            registerTarget(scope, u.getTable(), Access.UPDATE);
            addFromItem(scope, u.getFromItem());
            addJoins(scope, u.getJoins());
            for (UpdateSet us : u.getUpdateSets()) {
                List<String> targets = new ArrayList<>();
                for (Column c : us.getColumns()) {
                    targets.add(column(c, scope, Access.UPDATE, "set clause"));
                }
                ExpressionList<?> values = us.getValues();
                for (int k = 0; k < values.size(); k++) {
                    Expression v = values.get(k);
                    String target = values.size() == targets.size() ? targets.get(k) : null;
                    walk(v, scope, "set expression", target == null ? null : new Ctx(target, "=", v instanceof JdbcParameter));
                }
            }
            if (u.getWhere() != null) {
                predicates.add("WHERE " + abbrev(u.getWhere()));
                walk(u.getWhere(), scope, "where", null);
            }
        }

        void delete(Delete d) {
            handleWith(d.getWithItemsList());
            Scope scope = new Scope(null);
            boolean multi = d.getTables() != null && !d.getTables().isEmpty();
            if (multi) {
                addFromItem(scope, d.getTable());
                tables.removeIf(t -> t.access() == Access.READ && d.getTable() != null && t.name().equals(tableName(d.getTable())));
                for (Table target : d.getTables()) {
                    Source src = scope.find(Names.norm(target.getName()));
                    tables.add(new TableUse(src != null && src.table() != null ? src.table() : tableName(target), null, Access.DELETE));
                }
            } else {
                registerTarget(scope, d.getTable(), Access.DELETE);
            }
            if (d.getUsingList() != null) {
                d.getUsingList().forEach(t -> addFromItem(scope, t));
            }
            addJoins(scope, d.getJoins());
            if (d.getWhere() != null) {
                predicates.add("WHERE " + abbrev(d.getWhere()));
                walk(d.getWhere(), scope, "where", null);
            }
        }

        void execute(Execute e) {
            routines.add(Names.norm(e.getName()));
            if (e.getExprList() != null) {
                for (Object arg : e.getExprList()) {
                    walk((Expression) arg, new Scope(null), "procedure argument", null);
                }
            }
        }

        void registerTarget(Scope scope, Table t, Access access) {
            String name = tableName(t);
            String alias = t.getAlias() == null ? null : Names.norm(t.getAlias().getName());
            if (Names.isDynamic(simple(name))) {
                dynamic = true;
                scope.sources.add(new Source(Kind.TABLE, alias != null ? alias : name, null, null));
                return;
            }
            scope.sources.add(new Source(Kind.TABLE, alias != null ? alias : simple(name), name, null));
            tables.add(new TableUse(name, alias, access));
        }

        // -------------------------------------------------------------- expressions

        /** Walks an expression and returns "table.column" when it is a column that resolved to a base table. */
        String walk(Expression e, Scope scope, String role, Ctx ctx) {
            if (e == null) {
                return null;
            }
            if (e instanceof Select s) {
                subqueries++;
                analyzeSelect(s, scope, false);
                return null;
            }
            if (e instanceof ExpressionList<?> list) {
                for (Object o : list) {
                    walk((Expression) o, scope, role, ctx == null ? null : ctx.indirect());
                }
                return null;
            }
            if (e instanceof Column c) {
                return column(c, scope, Access.READ, role);
            }
            if (e instanceof JdbcParameter p) {
                param(p.getIndex(), null, role, ctx);
                return null;
            }
            if (e instanceof JdbcNamedParameter np) {
                param(null, np.getName(), role, ctx);
                return null;
            }
            if (e instanceof Between b) {
                String l = walk(b.getLeftExpression(), scope, role, null);
                Expression s = b.getBetweenExpressionStart();
                Expression en = b.getBetweenExpressionEnd();
                walk(s, scope, role, l == null ? null : new Ctx(l, ">=", s instanceof JdbcParameter));
                walk(en, scope, role, l == null ? null : new Ctx(l, "<=", en instanceof JdbcParameter));
                return null;
            }
            if (e instanceof InExpression in) {
                String l = walk(in.getLeftExpression(), scope, role, null);
                String op = in.isNot() ? "NOT IN" : "IN";
                Expression right = in.getRightExpression();
                if (right instanceof ExpressionList<?> items) {
                    for (Object o : items) {
                        walk((Expression) o, scope, role, l == null ? null : new Ctx(l, op, o instanceof JdbcParameter));
                    }
                } else {
                    walk(right, scope, role, l == null ? null : new Ctx(l, op, false));
                }
                return null;
            }
            if (e instanceof IsNullExpression n) {
                walk(n.getLeftExpression(), scope, role, null);
                return null;
            }
            if (e instanceof ExistsExpression ex) {
                walk(ex.getRightExpression(), scope, role, null);
                return null;
            }
            if (e instanceof NotExpression n) {
                return walk(n.getExpression(), scope, role, null);
            }
            if (e instanceof Parenthesis p) {
                return walk(p.getExpression(), scope, role, ctx);
            }
            if (e instanceof SignedExpression s) {
                return walk(s.getExpression(), scope, role, ctx == null ? null : ctx.indirect());
            }
            if (e instanceof CastExpression c) {
                walk(c.getLeftExpression(), scope, role, ctx == null ? null : ctx.indirect());
                return null;
            }
            if (e instanceof CaseExpression c) {
                walk(c.getSwitchExpression(), scope, role, null);
                if (c.getWhenClauses() != null) {
                    for (WhenClause w : c.getWhenClauses()) {
                        walk(w.getWhenExpression(), scope, role, null);
                        walk(w.getThenExpression(), scope, role, null);
                    }
                }
                walk(c.getElseExpression(), scope, role, null);
                return null;
            }
            if (e instanceof Function f) {
                String name = Names.norm(f.getName());
                (AGGREGATES.contains(name) ? aggregates : functions).add(name);
                if (f.getParameters() != null) {
                    for (Object o : f.getParameters()) {
                        walk((Expression) o, scope, role, ctx == null ? null : ctx.indirect());
                    }
                }
                return null;
            }
            if (e instanceof BinaryExpression b) {
                return binary(b, scope, role, ctx);
            }
            if (isLiteral(e)) {
                return null;
            }
            unsupported.add("expression " + e.getClass().getSimpleName());
            return null;
        }

        String binary(BinaryExpression b, Scope scope, String role, Ctx ctx) {
            Expression l = b.getLeftExpression();
            Expression r = b.getRightExpression();
            if (b instanceof AndExpression || b instanceof OrExpression || b instanceof XorExpression) {
                walk(l, scope, role, null);
                walk(r, scope, role, null);
                return null;
            }
            boolean comparison = b instanceof ComparisonOperator || b instanceof LikeExpression;
            if (!comparison) { // arithmetic, concatenation: a parameter inside is no longer a direct operand
                walk(l, scope, role, ctx == null ? null : ctx.indirect());
                walk(r, scope, role, ctx == null ? null : ctx.indirect());
                return null;
            }
            String op = b.getStringExpression().trim().toUpperCase(Locale.ROOT);
            if (b instanceof LikeExpression like && like.isNot()) {
                op = "NOT " + op;
            }
            if (l instanceof Column) {
                String lres = walk(l, scope, role, null);
                walk(r, scope, role, lres == null ? null : new Ctx(lres, op, r instanceof JdbcParameter));
            } else if (r instanceof Column) {
                String rres = walk(r, scope, role, null);
                walk(l, scope, role, rres == null ? null : new Ctx(rres, op, l instanceof JdbcParameter));
            } else {
                walk(l, scope, role, null);
                walk(r, scope, role, null);
            }
            return null;
        }

        static boolean isLiteral(Expression e) {
            return e instanceof LongValue || e instanceof DoubleValue || e instanceof StringValue
                    || e instanceof NullValue || e instanceof DateValue || e instanceof TimeValue
                    || e instanceof TimestampValue || e instanceof BooleanValue || e instanceof HexValue
                    || e instanceof DateTimeLiteralExpression || e instanceof IntervalExpression
                    || e instanceof TimeKeyExpression;
        }

        void param(Integer index, String name, String role, Ctx ctx) {
            int i;
            if (index != null) {
                i = index;
            } else if (name != null) {
                i = namedParams.computeIfAbsent(name, k -> namedParams.size() + 1);
            } else {
                i = ++fallbackIndex;
            }
            params.add(new Param(i, name, ctx == null ? null : ctx.column(), ctx == null ? null : ctx.operator(), role,
                    ctx != null && ctx.direct()));
        }

        // -------------------------------------------------------------- column resolution

        /** Records a column access and returns "table.column" when it belongs to a base table. */
        String column(Column c, Scope scope, Access access, String role) {
            String name = Names.norm(c.getColumnName());
            if (Names.isDynamic(name)) {
                dynamic = true;
                return null;
            }
            String qualifier = c.getTable() == null || c.getTable().getName() == null ? null
                    : qualified(c.getTable().getFullyQualifiedName());
            if (qualifier != null) {
                Source src = scope.find(qualifier);
                if (src == null) {
                    columns.add(new ColumnUse(null, name, access, role, "qualifier '" + qualifier + "' not found in the FROM clause"));
                    return null;
                }
                return recordFrom(src, name, access, role, null);
            }
            return resolveUnqualified(name, scope, access, role);
        }

        String resolveUnqualified(String name, Scope scope, Access access, String role) {
            for (Scope s = scope; s != null; s = s.parent) {
                List<Source> candidates = new ArrayList<>();
                for (Source src : s.sources) {
                    boolean possible = switch (src.kind()) {
                        case TABLE -> src.table() == null || !catalog.knowsTable(src.table())
                                || catalog.hasColumn(src.table(), name);
                        case DERIVED, CTE -> src.labels() == null || src.labels().contains(name);
                    };
                    if (possible) {
                        candidates.add(src);
                    }
                }
                if (candidates.size() == 1) {
                    // the only source that can own the column (others are known not to have it): sound by elimination
                    return recordFrom(candidates.get(0), name, access, role, null);
                }
                if (candidates.isEmpty() && s.sources.size() == 1 && s.sources.get(0).kind() == Kind.TABLE
                        && s.sources.get(0).table() != null) {
                    // a single table is in scope, so the column can only belong to it, but the schema does not list it
                    Source only = s.sources.get(0);
                    return recordFrom(only, name, access, role, "not declared in the known schema of " + only.table());
                }
                if (candidates.size() > 1) {
                    columns.add(new ColumnUse(null, name, access, role, "ambiguous between "
                            + candidates.stream().map(Source::alias).collect(Collectors.joining(", "))
                            + ": qualify the column or supply schema (DDL)"));
                    return null;
                }
            }
            columns.add(new ColumnUse(null, name, access, role, "no source in scope contains this column"));
            return null;
        }

        String recordFrom(Source src, String name, Access access, String role, String note) {
            if (src.kind() != Kind.TABLE) {
                return null; // read inside the derived table or CTE; the base access is recorded there
            }
            if (src.table() == null) {
                columns.add(new ColumnUse(null, name, access, role, "table is built dynamically"));
                return null;
            }
            columns.add(new ColumnUse(src.table(), name, access, role, note));
            return src.table() + "." + name;
        }
    }
}

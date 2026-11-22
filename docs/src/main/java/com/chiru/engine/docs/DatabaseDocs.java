package com.chiru.engine.docs;

import com.chiru.engine.model.*;
import java.util.*;

/** 05 database inventory, 06 SQL analysis. */
final class DatabaseDocs {

    private static final List<RelationType> TABLE_ACCESS = List.of(RelationType.READS_TABLE, RelationType.INSERTS_INTO,
            RelationType.UPDATES_TABLE, RelationType.DELETES_FROM);
    private static final List<RelationType> COLUMN_ACCESS = List.of(RelationType.READS_COLUMN, RelationType.INSERTS_COLUMN,
            RelationType.UPDATES_COLUMN);

    private DatabaseDocs() {}

    // ---------------------------------------------------------------- 05

    static String inventory(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("05 Database inventory",
                "Tables and columns found, and how each is read or written."));
        sb.append("> A table or column is listed only with SQL, ORM or schema evidence. A column is attributed to a table only "
                + "when the SQL qualifies it, the schema decides it, or it is the only possible owner; otherwise it is listed "
                + "under *unresolved columns*. Names are never inferred from Java variable names.\n\n");

        List<Entity> tables = new ArrayList<>(c.of(EntityType.TABLE));
        tables.sort(Comparator.comparing(Entity::name));
        sb.append(Md.h(2, "Tables"));
        List<List<String>> rows = new ArrayList<>();
        for (Entity t : tables) {
            List<String> row = new ArrayList<>(List.of(t.name(), Optional.ofNullable(c.attr(t, "source")).orElse("SQL only")));
            for (RelationType type : TABLE_ACCESS) {
                row.add(String.valueOf(c.index.in(t.id(), type).size()));
            }
            row.add(c.loc(t));
            rows.add(row);
        }
        sb.append(Md.table(List.of("Table", "Schema source", "Reads", "Inserts", "Updates", "Deletes", "Evidence"), rows));
        sb.append("_Reads, inserts, updates and deletes count the SQL statements that touch the table._\n\n");

        for (Entity t : tables) {
            sb.append(Md.h(3, "Table " + t.name()));
            List<List<String>> cols = new ArrayList<>();
            for (Relationship contains : c.index.out(t.id(), RelationType.CONTAINS)) {
                if (contains.to().type() != EntityType.COLUMN) {
                    continue;
                }
                Entity col = c.index.entity(contains.to());
                cols.add(columnRow(c, col));
            }
            cols.sort(Comparator.comparing(r -> r.get(0)));
            sb.append(Md.table(List.of("Column", "Declared type", "Read", "Inserted", "Updated", "Roles seen in SQL", "Evidence"), cols));
        }

        sb.append(Md.h(2, "Unresolved columns"));
        List<List<String>> unresolved = new ArrayList<>();
        for (Entity col : c.of(EntityType.COLUMN)) {
            if ("true".equals(c.attr(col, "unresolved"))) {
                unresolved.add(columnRow(c, col));
            }
        }
        sb.append(Md.table(List.of("Column", "Declared type", "Read", "Inserted", "Updated", "Roles seen in SQL", "Evidence"), unresolved));
        return sb.toString();
    }

    private static List<String> columnRow(DocContext c, Entity col) {
        Set<String> roles = new TreeSet<>();
        int[] counts = new int[3];
        for (int i = 0; i < COLUMN_ACCESS.size(); i++) {
            for (Relationship r : c.index.in(col.id(), COLUMN_ACCESS.get(i))) {
                counts[i]++;
                r.evidence().stream().map(Evidence::note).filter(Objects::nonNull).forEach(roles::add);
            }
        }
        String note = "true".equals(c.attr(col, "notInSchema")) ? " (not in the declared schema)" : "";
        return List.of(col.name() + note, Optional.ofNullable(c.attr(col, "type")).orElse(Md.NONE), String.valueOf(counts[0]),
                String.valueOf(counts[1]), String.valueOf(counts[2]), roles.isEmpty() ? Md.NONE : String.join("; ", roles), c.loc(col));
    }

    // ---------------------------------------------------------------- 06

    static String sqlAnalysis(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("06 SQL analysis", "Every SQL statement found, its tables, and its parameters."));
        sb.append("> Statements are found in Java string constants and concatenations, MyBatis mapper XML and `.sql` files. "
                + "Dynamic SQL (parts not constant) is flagged and everything derived from it is PARTIAL. JPQL/HQL is reported "
                + "and not analysed as SQL. Parameter bindings are derived for JDBC statements held in local variables.\n\n");
        List<Entity> statements = new ArrayList<>(c.of(EntityType.SQL_STATEMENT));
        statements.sort(Comparator.comparing(s -> s.id().value()));
        if (statements.isEmpty()) {
            return sb.append("_No SQL statement was found._\n").toString();
        }
        List<List<String>> index = new ArrayList<>();
        for (Entity s : statements) {
            index.add(List.of(s.name(), Optional.ofNullable(c.attr(s, "type")).orElse(Md.NONE), Optional.ofNullable(c.attr(s, "origin")).orElse(Md.NONE),
                    Optional.ofNullable(c.attr(s, "parsed")).orElse(Md.NONE), Optional.ofNullable(c.attr(s, "dynamic")).orElse("false"), c.loc(s)));
        }
        sb.append(Md.table(List.of("Statement", "Type", "Origin", "Parsed", "Dynamic", "Evidence"), index));

        for (Entity s : statements) {
            sb.append(Md.h(2, s.name()));
            sb.append(Md.bullet("Model ID", Md.code(s.id().value())));
            sb.append(Md.bullet("Origin", c.attr(s, "origin")));
            sb.append(Md.bullet("Evidence", c.loc(s)));
            sb.append(Md.bullet("Executed by", String.join(", ", c.index.in(s.id(), RelationType.EXECUTES).stream()
                    .map(r -> ModelIndex.shortName(r.from()) + " (" + c.loc(c.index.entity(r.from())) + ")").toList())));
            sb.append('\n').append(Md.block("sql", Optional.ofNullable(c.attr(s, "text")).orElse("")));
            if (c.attr(s, "error") != null) {
                sb.append("**Parse error:** ").append(Md.code(c.attr(s, "error"))).append("\n\n");
            }
            for (String key : List.of("joins", "predicates", "groupBy", "orderBy", "aggregates", "ctes", "subqueries", "functions",
                    "routines", "unsupported", "notes")) {
                if (c.attr(s, key) != null) {
                    sb.append(Md.bullet(key, c.attr(s, key)));
                }
            }
            sb.append("\n**Tables:** ");
            List<String> access = new ArrayList<>();
            for (RelationType t : TABLE_ACCESS) {
                c.index.out(s.id(), t).forEach(r -> access.add(c.name(r.to()) + " (" + t.name() + ", " + r.status() + ")"));
            }
            sb.append(access.isEmpty() ? Md.NONE : String.join(", ", access)).append("\n\n");

            List<List<String>> params = new ArrayList<>();
            for (Relationship contains : c.index.out(s.id(), RelationType.CONTAINS)) {
                if (contains.to().type() != EntityType.PARAMETER) {
                    continue;
                }
                Entity p = c.index.entity(contains.to());
                String source = String.join(", ", c.index.out(p.id(), RelationType.DERIVED_FROM).stream()
                        .filter(r -> r.to().type() == EntityType.COLUMN).map(r -> c.name(r.to()) + " [" + r.status() + "]").toList());
                if ("IN".equals(c.attr(p, "direction"))) {
                    params.add(List.of("IN", "#" + c.attr(p, "index"), Optional.ofNullable(c.attr(p, "column")).orElse(Md.NONE),
                            Optional.ofNullable(c.attr(p, "role")).orElse(Md.NONE),
                            Optional.ofNullable(c.attr(p, "javaExpression")).orElse("not derived"),
                            Optional.ofNullable(c.attr(p, "javaVariables")).orElse(Md.NONE)));
                } else {
                    params.add(List.of("OUT", c.attr(p, "label") + " (select item " + c.attr(p, "position") + ")", source,
                            Optional.ofNullable(c.attr(p, "expression")).orElse(Md.NONE),
                            Optional.ofNullable(c.attr(p, "javaExpression")).orElse("not derived"),
                            Optional.ofNullable(c.attr(p, "flowsTo")).map(f -> "flows to " + shorten(f)).orElse(Md.NONE)));
                }
            }
            sb.append(Md.table(List.of("Dir", "Parameter / select item", "Column", "Role / SQL expression", "Java expression", "Java variables / flows to"),
                    params));
        }
        return sb.toString();
    }

    private static String shorten(String flowsTo) {
        return flowsTo.replaceAll("method:([\\w.$]+\\.)?([\\w$]+)#(<init>|[\\w$]+)\\([^)]*\\)", "$2.$3");
    }
}

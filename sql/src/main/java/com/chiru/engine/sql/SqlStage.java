package com.chiru.engine.sql;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.model.AnalysisWarning;
import com.chiru.engine.model.Entity;
import com.chiru.engine.model.SourceLocation;
import com.chiru.engine.spoon.SpoonModelLoader;
import com.chiru.engine.sql.analysis.SchemaCatalog;
import com.chiru.engine.sql.analysis.SqlAnalysis;
import com.chiru.engine.sql.analysis.SqlAnalyzer;
import com.chiru.engine.sql.collect.ConfigSqlCollector;
import com.chiru.engine.sql.collect.JavaSqlCollector;
import com.chiru.engine.sql.collect.MapperXmlCollector;
import com.chiru.engine.sql.collect.SchemaDef;
import com.chiru.engine.sql.collect.SqlFileCollector;
import com.chiru.engine.sql.collect.SqlOrigin;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds SQL in Java strings, MyBatis mapper XML and {@code .sql} files, reads schema from DDL and ORM annotations,
 * parses every statement and records which tables and columns are read, inserted, updated or deleted, how query
 * parameters and result columns connect to Java code, and everything that could not be analysed.
 *
 * <p>Run after discovery and (for method links) after the source stage.
 */
public final class SqlStage implements AnalysisStage {

    public static final String NAME = "sql";
    private static final Logger LOG = LoggerFactory.getLogger(SqlStage.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) {
        AnalysisModel model = ctx.model();
        List<SqlOrigin> origins = new ArrayList<>();
        List<SchemaDef> schemas = new ArrayList<>();

        Map<String, List<SqlOrigin>> aliases = new LinkedHashMap<>();
        collectFiles(model, origins, schemas, aliases);
        JavaSqlCollector.Result java = collectJava(ctx, model, aliases);
        if (java != null) {
            attachConfigUses(origins, java.configUses());
            origins.addAll(java.origins());
            schemas.addAll(java.schemas());
            if (!java.jpqlSkipped().isEmpty()) {
                model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO, java.jpqlSkipped().size()
                        + " JPQL/HQL query string(s) were not analysed because they are not SQL (e.g. "
                        + String.join("; ", java.jpqlSkipped().stream().limit(3).toList()) + ")", null));
            }
            if (java.sqlLikeIgnored() > 0) {
                model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO, java.sqlLikeIgnored()
                        + " string(s) start with a SQL keyword but do not have the shape of a SQL statement and were ignored", null));
            }
        }

        SchemaCatalog catalog = new SchemaCatalog();
        for (SchemaDef s : schemas) {
            catalog.addTable(s.table());
            s.columns().forEach(c -> catalog.addColumn(s.table(), c.name(), c.type()));
        }
        SqlModelWriter writer = new SqlModelWriter(model, catalog);
        schemas.forEach(writer::writeSchema);

        SqlAnalyzer analyzer = new SqlAnalyzer(catalog);
        int unparsedIgnored = 0;
        for (SqlOrigin o : origins) {
            try {
                SqlAnalysis analysis = analyzer.analyze(o.text());
                writer.writeStatement(o, analysis);
            } catch (Exception | StackOverflowError e) {
                if (o.kind() == SqlOrigin.Kind.JAVA && !hasSqlConsumer(o)) {
                    unparsedIgnored++; // looks like SQL but nothing proves it is, and it does not parse
                    continue;
                }
                writer.writeUnparsed(o, firstLine(e));
            }
        }
        if (unparsedIgnored > 0) {
            model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO, unparsedIgnored
                    + " SQL-like Java string(s) with no recognised SQL consumer could not be parsed and were ignored", null));
        }
        writer.summarize();
        LOG.info("SQL: {} statement(s), {} schema table(s)", origins.size(), schemas.size());
    }

    private static boolean hasSqlConsumer(SqlOrigin o) {
        return o.java() != null && o.java().consumers().stream().anyMatch(c -> !c.api().equals("unknown"));
    }

    /** SQL kept in XML, properties or YAML gets the Java code that looks it up and runs it. */
    private static void attachConfigUses(List<SqlOrigin> origins, Map<String, List<SqlOrigin.Consumer>> uses) {
        for (int i = 0; i < origins.size(); i++) {
            SqlOrigin o = origins.get(i);
            List<SqlOrigin.Consumer> cs = uses.get(o.key());
            if (cs != null && !cs.isEmpty()) {
                origins.set(i, new SqlOrigin(o.kind(), o.key(), o.location(), o.text(), o.dynamic(), o.notes(), o.paramNames(),
                        new SqlOrigin.JavaUse(null, new ArrayList<>(new LinkedHashSet<>(cs)))));
            }
        }
    }

    private JavaSqlCollector.Result collectJava(StageContext ctx, AnalysisModel model, Map<String, List<SqlOrigin>> aliases) {
        Optional<SpoonModelLoader.Loaded> loaded = SpoonModelLoader.loadShared(ctx, NAME, false);
        return loaded.map(l -> new JavaSqlCollector(l.model(), l.displayPaths(), id -> model.entity(id).isPresent(), aliases).collect())
                .orElse(null);
    }

    private void collectFiles(AnalysisModel model, List<SqlOrigin> origins, List<SchemaDef> schemas, Map<String, List<SqlOrigin>> aliases) {
        int otherStatements = 0;
        for (Entity file : DiscoveryStage.filesOfKind(model, "SQL")) {
            try {
                String content = Files.readString(Path.of(file.attributes().get("absolutePath")));
                SqlFileCollector.Result r = SqlFileCollector.collect(file.name(), content);
                origins.addAll(r.origins());
                schemas.addAll(r.schemas());
                otherStatements += r.otherStatements();
                r.problems().forEach(p -> model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.WARNING, p, null)));
            } catch (IOException e) {
                model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.WARNING,
                        "Could not read " + file.name() + ": " + e.getMessage(), new SourceLocation(file.name(), 1, 1)));
            }
        }
        if (otherStatements > 0) {
            model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO, otherStatements
                    + " non-DML statement(s) in .sql files (DDL other than CREATE TABLE, grants, scripts) were not analysed", null));
        }
        int configStatements = 0;
        for (Entity file : DiscoveryStage.filesOfKind(model, "XML")) {
            try {
                Path abs = Path.of(file.attributes().get("absolutePath"));
                MapperXmlCollector.Result r;
                try (InputStream in = Files.newInputStream(abs)) {
                    r = MapperXmlCollector.collect(file.name(), in);
                }
                if (r.isMapper()) {
                    for (SqlOrigin o : r.origins()) {
                        String id = o.key().substring(o.key().indexOf('#') + 1);
                        register(aliases, o, id, id.contains(".") ? id.substring(id.lastIndexOf('.') + 1) : null);
                    }
                    origins.addAll(r.origins());
                    r.problems().forEach(p -> model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.WARNING, p, null)));
                    continue;
                }
                ConfigSqlCollector.Result c;
                try (InputStream in = Files.newInputStream(abs)) {
                    c = ConfigSqlCollector.xml(file.name(), in);
                }
                configStatements += addConfig(c, origins, aliases, model);
            } catch (IOException e) {
                model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.WARNING,
                        "Could not read " + file.name() + ": " + e.getMessage(), new SourceLocation(file.name(), 1, 1)));
            }
        }
        for (String kind : List.of("PROPERTIES", "YAML")) {
            for (Entity file : DiscoveryStage.filesOfKind(model, kind)) {
                try {
                    String content = Files.readString(Path.of(file.attributes().get("absolutePath")));
                    configStatements += addConfig(kind.equals("YAML") ? ConfigSqlCollector.yaml(file.name(), content)
                            : ConfigSqlCollector.properties(file.name(), content), origins, aliases, model);
                } catch (IOException e) {
                    model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.WARNING,
                            "Could not read " + file.name() + ": " + e.getMessage(), new SourceLocation(file.name(), 1, 1)));
                }
            }
        }
        if (configStatements > 0) {
            model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO, configStatements
                    + " SQL statement(s) are configured outside Java (XML, .properties or .yml) and were linked to Java only where a "
                    + "string literal equals their key", null));
        }
    }

    private static int addConfig(ConfigSqlCollector.Result c, List<SqlOrigin> origins, Map<String, List<SqlOrigin>> aliases, AnalysisModel model) {
        origins.addAll(c.origins());
        c.aliases().forEach((k, v) -> v.forEach(o -> register(aliases, o, k)));
        c.problems().forEach(p -> model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.WARNING, p, null)));
        return c.origins().size();
    }

    private static void register(Map<String, List<SqlOrigin>> aliases, SqlOrigin o, String... keys) {
        for (String k : keys) {
            if (k != null && !k.isBlank()) {
                List<SqlOrigin> l = aliases.computeIfAbsent(k, x -> new ArrayList<>());
                if (!l.contains(o)) {
                    l.add(o);
                }
            }
        }
    }

    private static String firstLine(Throwable e) {
        String m = String.valueOf(e.getMessage());
        int nl = m.indexOf('\n');
        return (nl < 0 ? m : m.substring(0, nl)).trim();
    }
}

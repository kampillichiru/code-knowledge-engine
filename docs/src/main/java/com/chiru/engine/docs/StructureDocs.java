package com.chiru.engine.docs;

import com.chiru.engine.core.CallGraphMermaid;
import com.chiru.engine.model.*;
import java.util.*;
import java.util.stream.Collectors;

/** 00 index, 01 module overview, 02 functionality inventory, 03 execution flow, 10 class and method dependencies. */
final class StructureDocs {

    private static final int MAX_FLOWS = 6;
    private static final int MAX_ROWS = 500;

    private StructureDocs() {}

    // ---------------------------------------------------------------- 00

    static String index(DocContext c, Map<String, String> titles) {
        StringBuilder sb = new StringBuilder(c.header("Analysis documentation", "Start here."));
        sb.append(Md.h(2, "Documents"));
        List<List<String>> rows = new ArrayList<>();
        titles.forEach((file, title) -> rows.add(List.of("[" + file + "](" + file + ")", title)));
        sb.append(Md.table(List.of("File", "What it answers"), rows));
        sb.append(Md.h(2, "How to read these documents"));
        sb.append("- Each statement carries `file:startLine-endLine` evidence. Open the file at that line to check it.\n");
        sb.append("- **BR-nnnn** numbers rules in the order of their model ID; the full ID is always given next to it. "
                + "A number can change when code is added; the model ID is the stable key.\n");
        sb.append("- Output fields are traced per scenario. When the same field is derived differently in two scenarios "
                + "the paths are shown separately, never merged.\n");
        sb.append("- A VERIFIED trace means every link was verified. PARTIAL and UNRESOLVED are not failures of the tool: "
                + "they mark where static analysis cannot be sure. [12_Open_Questions_And_Gaps.md](12_Open_Questions_And_Gaps.md) "
                + "lists every one.\n");
        sb.append("- Plain-language explanations are deliberately not written here. Use the Copilot prompts in "
                + "`.github/prompts`, which must quote this evidence and label their wording AI_INTERPRETED.\n\n");
        sb.append(Md.h(2, "Model at a glance"));
        Map<EntityType, Long> counts = new TreeMap<>();
        c.model.entities().forEach(e -> counts.merge(e.type(), 1L, Long::sum));
        sb.append(Md.table(List.of("Entity type", "Count"), counts.entrySet().stream()
                .map(e -> List.of(e.getKey().name(), String.valueOf(e.getValue()))).toList()));
        sb.append("Relationships: ").append(c.model.relationships().size()).append(". Warnings: ")
                .append(c.model.warnings().size()).append(".\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 01

    static String moduleOverview(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("01 Module overview", "What was analysed and how it is organised."));
        sb.append(Md.h(2, "Repositories"));
        sb.append(Md.table(List.of("Repository", "Evidence"), c.of(EntityType.REPOSITORY).stream()
                .map(r -> List.of(r.name(), c.loc(r))).toList()));

        sb.append(Md.h(2, "Modules and build metadata"));
        Set<String> analysed = new HashSet<>();
        Set<String> ownGroups = new HashSet<>();
        for (Entity m : c.of(EntityType.MODULE)) {
            if (c.attr(m, "groupId") != null && c.attr(m, "artifactId") != null) {
                analysed.add(c.attr(m, "groupId") + ":" + c.attr(m, "artifactId"));
                ownGroups.add(c.attr(m, "groupId"));
            }
        }
        List<List<String>> modules = new ArrayList<>();
        for (Entity m : c.of(EntityType.MODULE)) {
            String coords = Optional.ofNullable(c.attr(m, "groupId")).orElse("?") + ":" + Optional.ofNullable(c.attr(m, "artifactId")).orElse("?")
                    + ":" + Optional.ofNullable(c.attr(m, "version")).orElse("?")
                    + ("true".equals(c.attr(m, "coordinatesInherited")) ? " (groupId or version inherited from the parent)" : "");
            List<String> analysedDeps = new ArrayList<>();
            c.index.out(m.id(), RelationType.DEPENDS_ON).forEach(r -> analysedDeps.add(c.moduleLabel(c.index.entity(r.to()))));
            List<String> missing = new ArrayList<>();
            List<String> thirdParty = new ArrayList<>();
            String declared = c.attr(m, "dependencies");
            for (String dep : declared == null ? new String[0] : declared.split(", ")) {
                String[] p = dep.split(":");
                String coordinate = p.length < 2 ? dep : p[0] + ":" + p[1];
                if (analysed.contains(coordinate)) {
                    continue;                                  // shown as an analysed dependency
                }
                (ownGroups.contains(p[0]) ? missing : thirdParty).add(dep);
            }
            modules.add(List.of(c.moduleLabel(m), Optional.ofNullable(c.attr(m, "buildSystem")).orElse(Md.NONE),
                    c.attr(m, "artifactId") == null ? Md.NONE : coords, Optional.ofNullable(c.attr(m, "packaging")).orElse(Md.NONE),
                    analysedDeps.isEmpty() ? Md.NONE : String.join(", ", analysedDeps),
                    missing.isEmpty() ? Md.NONE : "**" + String.join(", ", missing) + "**",
                    thirdParty.isEmpty() ? Md.NONE : String.join(", ", thirdParty), c.loc(m)));
        }
        sb.append(Md.table(List.of("Module", "Build", "Coordinates", "Packaging", "Depends on (analysed)",
                "Project modules NOT analysed", "Other declared dependencies", "Evidence"), modules));
        sb.append("_Dependencies are read from the build file as written (the project version and group placeholders are filled in). "
                + "A dependency counts as a project module when it has the same groupId as an analysed module. **A module listed under "
                + "\"NOT analysed\" is outside the analysis: calls into it are external and any trace through it is PARTIAL.** Add its folder, or "
                + "the parent folder, to `--source`. Gradle scripts are inventoried but their dependencies are not parsed._\n\n");

        sb.append(Md.h(2, "File inventory"));
        Map<String, Long> byKind = new TreeMap<>();
        for (Entity f : c.of(EntityType.FILE)) {
            byKind.merge(Optional.ofNullable(c.attr(f, "kind")).orElse("?") + " (" + Optional.ofNullable(c.attr(f, "scope")).orElse("?") + ")",
                    1L, Long::sum);
        }
        sb.append(Md.table(List.of("File kind (scope)", "Files"), byKind.entrySet().stream()
                .map(e -> List.of(e.getKey(), String.valueOf(e.getValue()))).toList()));

        sb.append(Md.h(2, "Packages"));
        List<List<String>> packages = new ArrayList<>();
        for (Entity p : c.of(EntityType.PACKAGE)) {
            long classes = c.index.out(p.id(), RelationType.CONTAINS).stream().filter(r -> r.to().type() == EntityType.CLASS).count();
            packages.add(List.of(p.name(), String.valueOf(classes), c.loc(p)));
        }
        packages.sort(Comparator.comparing(r -> r.get(0)));
        sb.append(Md.table(List.of("Package", "Top-level classes", "Evidence"), packages));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 02

    static String functionality(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("02 Functionality inventory", "What the code contains and what it does at its edges."));
        sb.append(Md.h(2, "Entry points"));
        List<List<String>> entries = new ArrayList<>();
        for (Entity m : c.inSource(EntityType.METHOD)) {
            if (c.attr(m, "entryPoint") != null) {
                entries.add(withModule(c, c.moduleOf(m), List.of(ModelIndex.shortName(m.id()), c.attr(m, "entryPoint"), c.loc(m))));
            }
        }
        entries.sort(Comparator.comparing(r -> r.get(0)));
        sb.append(Md.table(headers(c, "Method", "Kind", "Evidence"), entries));

        sb.append(Md.h(2, "Business scenarios"));
        List<List<String>> scenarios = new ArrayList<>();
        for (Entity s : c.of(EntityType.SCENARIO)) {
            scenarios.add(List.of(s.name(), Optional.ofNullable(c.attr(s, "label")).orElse(Md.NONE),
                    Optional.ofNullable(c.attr(s, "outputFields")).orElse(Md.NONE), c.loc(s)));
        }
        sb.append(Md.table(List.of("Scenario (handler class)", "Trigger label", "Output fields", "Evidence"), scenarios));
        sb.append("Details: [11_Business_Scenario_Analysis.md](11_Business_Scenario_Analysis.md).\n\n");

        sb.append(Md.h(2, "Inputs and outputs"));
        List<List<String>> io = new ArrayList<>();
        for (Entity f : c.of(EntityType.OUTPUT_FILE)) {
            io.add(List.of("Output file", Optional.ofNullable(c.attr(f, "fixedWidth")).map(x -> "fixed-width").orElse("file"),
                    c.method(c.attr(f, "writerMethod")) + " via " + c.attr(f, "sink"), c.loc(f)));
        }
        for (Entity s : c.of(EntityType.SQL_STATEMENT)) {
            io.add(List.of("SQL " + Optional.ofNullable(c.attr(s, "type")).orElse("statement"), Optional.ofNullable(c.attr(s, "origin")).orElse(Md.NONE),
                    Md.code(Optional.ofNullable(c.attr(s, "text")).orElse("")), c.loc(s)));
        }
        sb.append(Md.table(List.of("Kind", "Detail", "Where", "Evidence"), io));

        sb.append(Md.h(2, "Classes"));
        List<List<String>> classes = new ArrayList<>();
        for (Entity k : c.inSource(EntityType.CLASS)) {
            long methods = c.index.out(k.id(), RelationType.CONTAINS).stream().filter(r -> r.to().type() == EntityType.METHOD).count();
            classes.add(withModule(c, c.moduleOf(k), List.of(ModelIndex.key(k.id()), Optional.ofNullable(c.attr(k, "kind")).orElse(Md.NONE),
                    Optional.ofNullable(c.attr(k, "stereotype")).orElse(Md.NONE), String.valueOf(methods), c.loc(k))));
        }
        classes.sort(Comparator.comparing(r -> r.get(0)));
        sb.append(Md.table(headers(c, "Class", "Kind", "Stereotype", "Methods", "Evidence"), classes));

        List<Entity> unreachable = c.inSource(EntityType.METHOD).stream().filter(m -> "no".equals(c.attr(m, "reachable"))).toList();
        sb.append(Md.h(2, "Methods not reachable from an entry point"));
        if (c.inSource(EntityType.METHOD).stream().noneMatch(m -> c.attr(m, "reachable") != null)) {
            sb.append("_Bytecode analysis did not run (no `--bytecode` root), so reachability is unknown._\n\n");
        } else {
            sb.append("_Over-approximation: a method listed here has no call path from an entry point in the analysed bytecode. "
                    + "Reflection, dependency injection and callbacks are not followed, so it may still run._\n\n");
            sb.append(Md.table(List.of("Method", "Evidence"), unreachable.stream()
                    .map(m -> List.of(ModelIndex.shortName(m.id()), c.loc(m))).sorted(Comparator.comparing(r -> r.get(0))).toList()));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 03

    static String executionFlow(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("03 Execution flow", "Possible call relationships from each entry point."));
        sb.append("> A call graph shows what *can* call what. Solid arrows are statically bound calls (VERIFIED); dashed arrows are "
                + "PARTIAL: dynamic dispatch (every compatible implementation is drawn) or a target outside the analysed code. "
                + "It is **not** a guaranteed runtime sequence; reflection, injection and callbacks are not resolved.\n\n");
        List<Entity> entries = c.inSource(EntityType.METHOD).stream().filter(m -> c.attr(m, "entryPoint") != null)
                .sorted(Comparator.comparing(m -> m.id().value())).toList();
        if (entries.isEmpty()) {
            sb.append("_No entry point (main method or framework entry annotation) was found, so no flow is drawn. "
                    + "The call edges are still listed in [10_Class_Method_Dependencies.md](10_Class_Method_Dependencies.md)._\n\n");
        }
        for (Entity entry : entries.stream().limit(MAX_FLOWS).toList()) {
            sb.append(Md.h(2, "From " + ModelIndex.shortName(entry.id())));
            sb.append("Entry: ").append(Md.code(ModelIndex.key(entry.id()))).append(" (").append(c.attr(entry, "entryPoint"))
                    .append("), evidence ").append(c.loc(entry)).append("\n\n");
            sb.append(Md.block("mermaid", CallGraphMermaid.render(c.model, entry.id(), 5, false)));
        }
        if (entries.size() > MAX_FLOWS) {
            sb.append("_").append(entries.size() - MAX_FLOWS).append(" more entry point(s) not drawn; see the model._\n\n");
        }

        sb.append(Md.h(2, "Call edges by certainty"));
        Map<TraceStatus, Long> byStatus = new EnumMap<>(TraceStatus.class);
        c.model.relationships().stream().filter(r -> r.type() == RelationType.CALLS)
                .forEach(r -> byStatus.merge(r.status(), 1L, Long::sum));
        sb.append(Md.table(List.of("Status", "Call edges", "Meaning"), List.of(
                List.of("VERIFIED", String.valueOf(byStatus.getOrDefault(TraceStatus.VERIFIED, 0L)), "binds statically to one analysed method"),
                List.of("PARTIAL", String.valueOf(byStatus.getOrDefault(TraceStatus.PARTIAL, 0L)),
                        "may be overridden, or the target is outside the analysed sources"),
                List.of("UNRESOLVED", String.valueOf(byStatus.getOrDefault(TraceStatus.UNRESOLVED, 0L)),
                        "the declaring type could not be identified (missing classpath)"))));

        sb.append(Md.h(2, "Most-called methods outside the analysed code"));
        Map<String, Long> external = new HashMap<>();
        c.model.relationships().stream().filter(r -> r.type() == RelationType.CALLS)
                .filter(r -> c.model.entity(r.to()).map(e -> "true".equals(c.attr(e, "external"))).orElse(false))
                .forEach(r -> external.merge(ModelIndex.key(r.to()), 1L, Long::sum));
        sb.append(Md.table(List.of("External method", "Call sites"), external.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).limit(15)
                .map(e -> List.of(e.getKey(), String.valueOf(e.getValue()))).toList()));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 10

    static String dependencies(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("10 Class and method dependencies", "Who extends, implements and calls whom."));
        sb.append(Md.h(2, "Inheritance and implementation"));
        List<List<String>> inh = new ArrayList<>();
        for (Relationship r : c.model.relationships()) {
            if (r.type() == RelationType.EXTENDS || r.type() == RelationType.IMPLEMENTS) {
                inh.add(List.of(ModelIndex.key(r.from()), r.type().name(), ModelIndex.key(r.to()), r.status().name(),
                        r.evidence().isEmpty() ? Md.NONE : c.loc(r.evidence().get(0).location())));
            }
        }
        inh.sort(Comparator.comparing((List<String> r) -> r.get(0)).thenComparing(r -> r.get(2)));
        sb.append(Md.table(List.of("Class", "Relation", "Type", "Status", "Evidence"), inh));

        sb.append(Md.h(2, "Class-level call graph"));
        Map<String, Integer> classCalls = new TreeMap<>();
        for (Relationship r : c.model.relationships()) {
            if (r.type() != RelationType.CALLS || !inSource(c, r.from()) || !inSource(c, r.to())) {
                continue;
            }
            String from = classOf(r.from());
            String to = classOf(r.to());
            if (!from.equals(to)) {
                classCalls.merge(from + "\u0000" + to, 1, Integer::sum);
            }
        }
        if (classCalls.isEmpty()) {
            sb.append("_No calls between different analysed classes._\n\n");
        } else {
            Map<String, String> ids = new LinkedHashMap<>();
            StringBuilder g = new StringBuilder("flowchart LR\n");
            classCalls.forEach((k, n) -> {
                String[] p = k.split("\u0000");
                for (String cls : p) {
                    ids.computeIfAbsent(cls, x -> "c" + ids.size());
                }
            });
            ids.forEach((cls, id) -> g.append("    ").append(id).append("[\"").append(cls.substring(cls.lastIndexOf('.') + 1)).append("\"]\n"));
            classCalls.forEach((k, n) -> {
                String[] p = k.split("\u0000");
                g.append("    ").append(ids.get(p[0])).append(" -->|").append(n).append("| ").append(ids.get(p[1])).append('\n');
            });
            sb.append("Arrows are labelled with the number of call edges between the two classes.\n\n");
            sb.append(Md.block("mermaid", g.toString()));
        }

        sb.append(moduleDependencies(c));

        sb.append(Md.h(2, "Methods"));
        List<List<String>> methods = new ArrayList<>();
        for (Entity m : c.inSource(EntityType.METHOD)) {
            long out = c.index.out(m.id(), RelationType.CALLS).stream().filter(r -> inSource(c, r.to())).count();
            long in = c.index.in(m.id(), RelationType.CALLS).size();
            methods.add(withModule(c, c.moduleOf(m), List.of(ModelIndex.shortName(m.id()), Optional.ofNullable(c.attr(m, "modifiers")).orElse(Md.NONE),
                    String.valueOf(out), String.valueOf(in), Optional.ofNullable(c.attr(m, "reachable")).orElse("not analysed"), c.loc(m))));
        }
        methods.sort(Comparator.comparing(r -> r.get(0)));
        boolean cut = methods.size() > MAX_ROWS;
        sb.append(Md.table(headers(c, "Method", "Modifiers", "Calls (analysed code)", "Called by", "Reachable from entry", "Evidence"),
                cut ? methods.subList(0, MAX_ROWS) : methods));
        if (cut) {
            sb.append("_Showing the first ").append(MAX_ROWS).append(" of ").append(methods.size()).append(" methods._\n\n");
        }
        return sb.toString();
    }

    /** Inserts the Module column (after the first) when the analysis covers more than one module. */
    private static List<String> headers(DocContext c, String... columns) {
        List<String> out = new ArrayList<>(List.of(columns));
        if (c.multiModule()) {
            out.add(1, "Module");
        }
        return out;
    }

    private static List<String> withModule(DocContext c, String module, List<String> row) {
        if (!c.multiModule()) {
            return row;
        }
        List<String> out = new ArrayList<>(row);
        out.add(1, module);
        return out;
    }

    /**
     * Module-level dependencies: what the build files declare next to what the code actually does. A declaration with no call
     * is a dependency the code may not need (or reaches only through reflection or configuration); calls with no declaration
     * mean the dependency comes in transitively, or the build file is incomplete.
     */
    private static String moduleDependencies(DocContext c) {
        StringBuilder sb = new StringBuilder(Md.h(2, "Module dependencies"));
        if (!c.multiModule()) {
            return sb.append("_The analysis covers a single module, so there are no dependencies between modules._\n\n").toString();
        }
        Map<String, Integer> calls = new TreeMap<>();
        Map<String, String> example = new HashMap<>();
        for (Relationship r : c.model.relationships()) {
            if (r.type() != RelationType.CALLS || !inSource(c, r.from()) || !inSource(c, r.to())) {
                continue;
            }
            String from = c.moduleOf(c.index.entity(r.from()));
            String to = c.moduleOf(c.index.entity(r.to()));
            if (!from.equals(to) && !from.equals(Md.NONE) && !to.equals(Md.NONE)) {
                String key = from + "\u0000" + to;
                calls.merge(key, 1, Integer::sum);
                example.putIfAbsent(key, ModelIndex.shortName(r.from()) + " calls " + ModelIndex.shortName(r.to()));
            }
        }
        Set<String> declared = new TreeSet<>();
        for (Relationship r : c.model.relationships()) {
            if (r.type() == RelationType.DEPENDS_ON && r.from().type() == EntityType.MODULE) {
                declared.add(c.moduleLabel(c.index.entity(r.from())) + "\u0000" + c.moduleLabel(c.index.entity(r.to())));
            }
        }
        Set<String> pairs = new TreeSet<>(calls.keySet());
        pairs.addAll(declared);
        List<List<String>> rows = new ArrayList<>();
        for (String pair : pairs) {
            String[] p = pair.split("\u0000");
            int n = calls.getOrDefault(pair, 0);
            rows.add(List.of(p[0], p[1], declared.contains(pair) ? "yes" : "no", String.valueOf(n),
                    n == 0 ? "declared, but no call between them was found" : declared.contains(pair) ? example.get(pair)
                            : "calls found but not declared directly (transitive, or the build file is incomplete): " + example.get(pair)));
        }
        sb.append("Arrows point from the module that uses to the module that is used; the number is the count of call edges.\n\n");
        if (!rows.isEmpty()) {
            Map<String, String> ids = new LinkedHashMap<>();
            StringBuilder g = new StringBuilder("flowchart LR\n");
            for (String pair : pairs) {
                for (String m : pair.split("\u0000")) {
                    ids.computeIfAbsent(m, x -> "m" + ids.size());
                }
            }
            ids.forEach((m, id) -> g.append("    ").append(id).append("[\"").append(m.replace("\"", "'")).append("\"]\n"));
            for (String pair : pairs) {
                String[] p = pair.split("\u0000");
                g.append("    ").append(ids.get(p[0])).append(calls.containsKey(pair) ? " -->|" + calls.get(pair) + "| " : " -.->|declared only| ")
                        .append(ids.get(p[1])).append('\n');
            }
            sb.append(Md.block("mermaid", g.toString()));
        }
        sb.append(Md.table(List.of("From module", "To module", "Declared in the build file", "Call edges", "Detail"), rows));
        return sb.toString();
    }

    private static boolean inSource(DocContext c, EntityId id) {
        return c.model.entity(id).map(e -> !"true".equals(c.attr(e, "external"))).orElse(false);
    }

    /** {@code method:com.x.Foo#bar()} gives {@code com.x.Foo}. */
    private static String classOf(EntityId method) {
        String key = ModelIndex.key(method);
        return key.substring(0, key.indexOf('#') < 0 ? key.length() : key.indexOf('#'));
    }

    static String joinSorted(Collection<String> values) {
        return values.stream().sorted().collect(Collectors.joining(", "));
    }
}

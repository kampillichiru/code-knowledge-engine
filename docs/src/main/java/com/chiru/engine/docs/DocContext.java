package com.chiru.engine.docs;

import com.chiru.engine.model.*;
import java.util.*;
import java.util.stream.Collectors;

/** What every document builder needs: the model, its index, and the human-facing rule numbers. */
final class DocContext {

    final AnalysisModel model;
    final ModelIndex index;
    final Humanizer humanizer;
    private final int glossarySize;
    private final Map<String, String> ruleLabels = new HashMap<>();

    DocContext(AnalysisModel model) {
        this(model, Map.of());
    }

    final Map<String, String> answers;

    DocContext(AnalysisModel model, Map<String, String> glossary) {
        this(model, glossary, Map.of());
    }

    DocContext(AnalysisModel model, Map<String, String> glossary, Map<String, String> answers) {
        this.answers = answers == null ? Map.of() : answers;
        this.model = model;
        this.humanizer = new Humanizer(glossary);
        this.glossarySize = glossary.size();
        this.index = new ModelIndex(model);
        List<Entity> rules = new ArrayList<>(model.entitiesOfType(EntityType.BUSINESS_RULE));
        rules.sort(Comparator.comparing(r -> r.id().value()));
        for (int i = 0; i < rules.size(); i++) {
            ruleLabels.put(rules.get(i).id().value(), String.format("BR-%04d", i + 1));
        }
    }

    int glossarySize() {
        return glossarySize;
    }

    private Map<String, String> moduleByFile;

    /** The name a module goes by in the documents: its artifactId when the build file has one, else its directory. */
    String moduleLabel(Entity module) {
        String artifact = module.attributes().get("artifactId");
        return artifact != null ? artifact : module.name();
    }

    /** The module the source file of this entity belongs to ("—" when the entity has no location or no module). */
    String moduleOf(Entity e) {
        SourceLocation l = ModelIndex.location(e);
        return l == null ? Md.NONE : moduleOfFile(l.file());
    }

    String moduleOfFile(String file) {
        if (moduleByFile == null) {
            moduleByFile = new HashMap<>();
            for (Entity f : model.entitiesOfType(EntityType.FILE)) {
                for (Relationship r : index.in(f.id(), RelationType.CONTAINS)) {
                    if (r.from().type() == EntityType.MODULE) {
                        moduleByFile.put(f.name(), moduleLabel(index.entity(r.from())));
                    }
                }
            }
        }
        return moduleByFile.getOrDefault(file, Md.NONE);
    }

    /** Table headers with a Module column inserted after the first when the analysis covers several modules. */
    List<String> headers(String... columns) {
        List<String> out = new ArrayList<>(List.of(columns));
        if (multiModule()) {
            out.add(1, "Module");
        }
        return out;
    }

    List<String> withModule(String module, List<String> row) {
        if (!multiModule()) {
            return row;
        }
        List<String> out = new ArrayList<>(row);
        out.add(1, module);
        return out;
    }

    /** More than one module holds analysed Java files: only then is a module column or graph worth showing. */
    boolean multiModule() {
        Set<String> modules = new HashSet<>();
        for (Entity f : model.entitiesOfType(EntityType.FILE)) {
            if ("JAVA".equals(f.attributes().get("kind")) && "main".equals(f.attributes().get("scope"))) {
                modules.add(moduleOfFile(f.name()));
            }
        }
        modules.remove(Md.NONE);
        return modules.size() > 1;
    }

    /** Header every document starts with. */
    String header(String title, String purpose) {
        return Md.h(1, title)
                + "_" + purpose + "_\n\n"
                + "> Generated from `analysis-model.json` (schema version " + AnalysisModel.SCHEMA_VERSION + "). "
                + "Everything here is **EXTRACTED**: deterministic facts from the code with file and line evidence. "
                + "No statement is an AI interpretation. Trace status: VERIFIED = every link verified, "
                + "PARTIAL = at least one link is an approximation, UNRESOLVED = the value cannot be traced statically.\n\n";
    }

    List<Entity> of(EntityType type) {
        return model.entitiesOfType(type);
    }

    List<Entity> inSource(EntityType type) {
        return of(type).stream().filter(e -> !"true".equals(e.attributes().get("external"))).toList();
    }

    String loc(Entity e) {
        SourceLocation l = ModelIndex.location(e);
        return l == null ? Md.NONE : l.toString();
    }

    String loc(SourceLocation l) {
        return l == null ? Md.NONE : l.toString();
    }

    /** {@code BR-0003} for a rule ID, or the ID itself when it is not a known rule. */
    String ruleLabel(String ruleId) {
        return ruleLabels.getOrDefault(ruleId, ruleId);
    }

    /** Rule labels for a comma-separated list of rule IDs. */
    String ruleLabels(String ids) {
        if (ids == null || ids.isBlank()) {
            return Md.NONE;
        }
        return Arrays.stream(ids.split(", ")).map(this::ruleLabel).sorted().distinct().collect(Collectors.joining(", "));
    }

    /** Key / name / scenario for an ID stored as an attribute, tolerating a missing attribute. */
    String keyOf(String id) {
        return id == null ? Md.NONE : ModelIndex.key(new EntityId(id));
    }

    String nameOf(String id) {
        return id == null ? Md.NONE : name(new EntityId(id));
    }

    Optional<Entity> scenarioOfId(String id) {
        return id == null ? Optional.empty() : scenarioOf(new EntityId(id));
    }

    String name(EntityId id) {
        return model.entity(id).map(Entity::name).orElse(ModelIndex.key(id));
    }

    String method(String methodId) {
        return methodId == null ? Md.NONE : ModelIndex.shortName(new EntityId(methodId));
    }

    String attr(Entity e, String key) {
        return e.attributes().get(key);
    }

    /** Output fields in layout order. */
    List<Entity> fields() {
        List<Entity> fields = new ArrayList<>(of(EntityType.OUTPUT_FIELD));
        fields.sort(Comparator.comparing((Entity f) -> attr(f, "file") == null ? "" : attr(f, "file"))
                .thenComparingInt(f -> Integer.parseInt(attr(f, "index") == null ? "0" : attr(f, "index"))));
        return fields;
    }

    /** The TRANSFORMATION entities of an output field with the status of the edge to each. */
    List<Map.Entry<Entity, TraceStatus>> traces(Entity field) {
        List<Map.Entry<Entity, TraceStatus>> traces = new ArrayList<>();
        for (Relationship r : index.out(field.id(), RelationType.DERIVED_FROM)) {
            if (r.to().type() == EntityType.TRANSFORMATION) {
                traces.add(Map.entry(index.entity(r.to()), r.status()));
            }
        }
        return traces;
    }

    /** The scenario an element belongs to, by PART_OF_SCENARIO. */
    Optional<Entity> scenarioOf(EntityId id) {
        return index.out(id, RelationType.PART_OF_SCENARIO).stream().map(r -> index.entity(r.to())).findFirst();
    }
}

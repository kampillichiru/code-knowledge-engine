package com.chiru.engine.discovery;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Inventories the analysed roots: repository, modules (Maven/Gradle build directories) and files by kind.
 * Evidence paths are {@code <repoName>/<path relative to the root>} so several roots never collide.
 */
public final class DiscoveryStage implements AnalysisStage {

    public static final String NAME = "discovery";
    private static final String EXTRACTOR = "discovery";
    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryStage.class);
    private static final Set<String> SKIP_DIRS =
            Set.of(".git", ".svn", ".idea", ".gradle", ".mvn", "target", "build", "node_modules", "out");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) throws IOException {
        List<PathMatcher> excludes = ctx.config().excludeGlobs().stream()
                .map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
        List<Path> roots = new ArrayList<>();
        for (Path configured : ctx.config().sourceRootPaths()) {
            Path abs = configured.toAbsolutePath().normalize();
            if (!Files.exists(abs)) {
                throw new IllegalArgumentException("Source root does not exist: " + abs);
            }
            roots.add(abs);
        }
        Map<Path, String> names = repositoryNames(roots);
        for (Path root : roots) {
            scanRoot(ctx.model(), root, excludes, names.get(root));
        }
        linkModules(ctx.model());
    }

    /**
     * A short name per root, used as the first segment of every evidence path. Two roots whose folders have the same name
     * (for example {@code common/src} and {@code module-1/src}) would otherwise be one repository and lose their module,
     * so colliding names are extended with the parent folder until they are unique.
     */
    static Map<Path, String> repositoryNames(List<Path> roots) {
        Map<Path, Path> bases = new LinkedHashMap<>();
        for (Path r : roots) {
            bases.put(r, Files.isDirectory(r) ? r : r.getParent());
        }
        Map<Path, String> names = new LinkedHashMap<>();
        for (Path r : roots) {
            names.put(r, baseName(bases.get(r), 1));
        }
        for (int depth = 2; depth <= 6; depth++) {
            Map<String, Set<Path>> byName = new HashMap<>();
            for (Path r : roots) {
                byName.computeIfAbsent(names.get(r), k -> new LinkedHashSet<>()).add(bases.get(r));
            }
            boolean clash = false;
            for (Path r : roots) {
                if (byName.get(names.get(r)).size() > 1) {
                    names.put(r, baseName(bases.get(r), depth));
                    clash = true;
                }
            }
            if (!clash) {
                break;
            }
        }
        return names;
    }

    private static String baseName(Path base, int segments) {
        List<String> parts = new ArrayList<>();
        Path p = base;
        for (int i = 0; i < segments && p != null && p.getFileName() != null; i++) {
            parts.add(0, p.getFileName().toString());
            p = p.getParent();
        }
        return parts.isEmpty() ? "root" : String.join("/", parts);
    }

    private void scanRoot(AnalysisModel model, Path root, List<PathMatcher> excludes, String repoName) throws IOException {
        Path base = Files.isDirectory(root) ? root : root.getParent();
        List<Path> files = new ArrayList<>();
        int[] excluded = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                boolean isRoot = dir.equals(root);
                return !isRoot && SKIP_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                Path rel = base.relativize(file);
                if (excludes.stream().anyMatch(m -> m.matches(rel))) {
                    excluded[0]++;
                } else {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());

        EntityId repoId = EntityId.of(EntityType.REPOSITORY, repoName);
        model.putEntity(new Entity(repoId, repoName, Map.of("root", base.toString()),
                List.of(fileEvidence(repoName, ""))));

        // Modules are directories holding a build descriptor.
        Map<String, String> moduleDirs = new TreeMap<>(); // relative dir -> build system
        for (Path f : files) {
            String fn = f.getFileName().toString();
            String dir = relDir(base, f);
            switch (fn) {
                case "pom.xml" -> moduleDirs.put(dir, "MAVEN");
                case "build.gradle", "build.gradle.kts" -> moduleDirs.putIfAbsent(dir, "GRADLE");
                default -> { }
            }
        }
        boolean needsDefault = files.stream().anyMatch(f -> owner(moduleDirs.keySet(), relDir(base, f)) == null);
        if (needsDefault) {
            moduleDirs.putIfAbsent("", "NONE");
        }

        for (var e : moduleDirs.entrySet()) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("buildSystem", e.getValue());
            attrs.put("directory", e.getKey().isEmpty() ? "." : e.getKey());
            if (e.getValue().equals("MAVEN")) {
                attrs.putAll(parsePom(base.resolve(e.getKey()).resolve("pom.xml"), model, repoName));
            } else if (e.getValue().equals("GRADLE")) {
                model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO,
                        "Gradle build script in '" + display(repoName, e.getKey()) + "' was inventoried but its "
                                + "dependencies are not parsed (unsupported in this version)", null));
            }
            EntityId moduleId = moduleId(repoName, e.getKey());
            model.putEntity(new Entity(moduleId, e.getKey().isEmpty() ? repoName : e.getKey(), attrs,
                    List.of(fileEvidence(repoName, e.getKey()))));
            model.addRelationship(contains(repoId, moduleId, repoName, e.getKey()));
        }

        Map<String, Integer> counts = new TreeMap<>();
        for (Path f : files) {
            String rel = base.relativize(f).toString().replace('\\', '/');
            String kind = classify(f.getFileName().toString());
            if (kind == null) {
                counts.merge("IGNORED", 1, Integer::sum);
                continue;
            }
            counts.merge(kind, 1, Integer::sum);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("kind", kind);
            attrs.put("absolutePath", f.toString());
            attrs.put("scope", rel.contains("src/test/") ? "test" : "main");
            EntityId fileId = EntityId.of(EntityType.FILE, repoName + "/" + rel);
            model.putEntity(new Entity(fileId, display(repoName, rel), attrs, List.of(fileEvidence(repoName, rel))));
            String module = owner(moduleDirs.keySet(), relDir(base, f));
            model.addRelationship(contains(moduleId(repoName, module), fileId, repoName, rel));
        }
        LOG.info("Discovered root '{}': {} modules, file counts {}, {} excluded by glob",
                repoName, moduleDirs.size(), counts, excluded[0]);
    }

    private static String classify(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("pom.xml") || n.startsWith("build.gradle") || n.startsWith("settings.gradle")) return "BUILD";
        if (n.endsWith(".java")) return "JAVA";
        if (n.endsWith(".sql")) return "SQL";
        if (n.endsWith(".xml")) return "XML";
        if (n.endsWith(".properties")) return "PROPERTIES";
        if (n.endsWith(".yml") || n.endsWith(".yaml")) return "YAML";
        if (n.endsWith(".json")) return "JSON";
        if (n.endsWith(".class") || n.endsWith(".jar")) return "BYTECODE";
        return null;
    }

    /** Nearest ancestor module directory of {@code dir}, or null. "" is the root directory. */
    private static String owner(Set<String> modules, String dir) {
        String best = null;
        for (String m : modules) {
            boolean inside = m.isEmpty() || dir.equals(m) || dir.startsWith(m + "/");
            if (inside && (best == null || m.length() > best.length())) {
                best = m;
            }
        }
        return best;
    }

    private static String relDir(Path base, Path file) {
        Path parent = base.relativize(file).getParent();
        return parent == null ? "" : parent.toString().replace('\\', '/');
    }

    private static EntityId moduleId(String repo, String dir) {
        return EntityId.of(EntityType.MODULE, repo + "/" + (dir.isEmpty() ? "." : dir));
    }

    private static String display(String repo, String rel) {
        return rel.isEmpty() ? repo : repo + "/" + rel;
    }

    private static Evidence fileEvidence(String repo, String rel) {
        return new Evidence(new SourceLocation(display(repo, rel), 1, 1), EXTRACTOR, Certainty.EXTRACTED,
                "file-level evidence");
    }

    private static Relationship contains(EntityId from, EntityId to, String repo, String rel) {
        return new Relationship(from, RelationType.CONTAINS, to, TraceStatus.VERIFIED,
                List.of(fileEvidence(repo, rel)));
    }

    /** Reads coordinates and declared dependencies from a pom.xml without resolving anything. */
    private Map<String, String> parsePom(Path pom, AnalysisModel model, String repoName) {
        Map<String, String> attrs = new LinkedHashMap<>();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = f.newDocumentBuilder().parse(pom.toFile());
            Element project = doc.getDocumentElement();
            // groupId and version are inherited from <parent> when the module does not set them
            Element parent = child(project, "parent");
            String groupId = firstNonBlank(childText(project, "groupId"), parent == null ? null : childText(parent, "groupId"));
            String version = firstNonBlank(childText(project, "version"), parent == null ? null : childText(parent, "version"));
            putIfPresent(attrs, "groupId", groupId);
            putIfPresent(attrs, "artifactId", childText(project, "artifactId"));
            putIfPresent(attrs, "version", version);
            putIfPresent(attrs, "packaging", childText(project, "packaging"));
            if (parent != null) {
                attrs.put("parent", childText(parent, "groupId") + ":" + childText(parent, "artifactId") + ":" + childText(parent, "version"));
                if (childText(project, "groupId") == null || childText(project, "version") == null) {
                    attrs.put("coordinatesInherited", "true");
                }
            }
            Element deps = child(project, "dependencies");
            if (deps != null) {
                List<String> list = new ArrayList<>();
                List<String> testList = new ArrayList<>();
                NodeList nodes = deps.getChildNodes();
                for (int i = 0; i < nodes.getLength(); i++) {
                    if (nodes.item(i) instanceof Element d && d.getTagName().equals("dependency")) {
                        String dep = resolve(childText(d, "groupId"), groupId, version) + ":" + childText(d, "artifactId")
                                + ":" + Objects.toString(resolve(childText(d, "version"), groupId, version), "managed");
                        ("test".equals(childText(d, "scope")) ? testList : list).add(dep);
                    }
                }
                if (!list.isEmpty()) {
                    attrs.put("dependencies", String.join(", ", list));
                }
                if (!testList.isEmpty()) {
                    attrs.put("testDependencies", String.join(", ", testList));
                }
            }
        } catch (Exception e) {
            model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.WARNING,
                    "Could not parse " + pom.getFileName() + " in " + repoName + ": " + e.getMessage(), null));
        }
        return attrs;
    }

    private static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                return e;
            }
        }
        return null;
    }

    private static String childText(Element parent, String tag) {
        Element e = child(parent, tag);
        return e == null ? null : e.getTextContent().trim();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** Replaces the common project placeholders in a dependency's groupId or version. */
    private static String resolve(String value, String groupId, String version) {
        if (value == null) {
            return null;
        }
        String out = value;
        if (version != null) {
            out = out.replace("${project.version}", version).replace("${pom.version}", version);
        }
        if (groupId != null) {
            out = out.replace("${project.groupId}", groupId).replace("${pom.groupId}", groupId);
        }
        return out;
    }

    /**
     * Links the modules that were analysed to each other ({@code MODULE DEPENDS_ON MODULE}, from the declared dependencies)
     * and warns about a declared dependency that looks like part of this project (same groupId as an analysed module) but
     * was not analysed: calls into it will be outside the analysed code and traces through it PARTIAL. Third-party and
     * test-scoped dependencies are not reported.
     */
    private static void linkModules(AnalysisModel model) {
        List<Entity> modules = new ArrayList<>();
        model.entitiesOfType(EntityType.MODULE).stream()
                .filter(m -> m.attributes().containsKey("groupId") && m.attributes().containsKey("artifactId"))
                .sorted(Comparator.comparing(m -> m.id().value())).forEach(modules::add);
        Map<String, Entity> byCoordinate = new HashMap<>();
        Set<String> ownGroups = new HashSet<>();
        for (Entity m : modules) {
            byCoordinate.putIfAbsent(m.attributes().get("groupId") + ":" + m.attributes().get("artifactId"), m);
            ownGroups.add(m.attributes().get("groupId"));
        }
        for (Entity m : modules) {
            String deps = m.attributes().get("dependencies");
            if (deps == null) {
                continue;
            }
            for (String dep : deps.split(", ")) {
                String[] parts = dep.split(":");
                if (parts.length < 2) {
                    continue;
                }
                String coordinate = parts[0] + ":" + parts[1];
                Entity target = byCoordinate.get(coordinate);
                if (target != null && !target.id().equals(m.id())) {
                    model.addRelationship(new Relationship(m.id(), RelationType.DEPENDS_ON, target.id(), TraceStatus.VERIFIED,
                            List.of(new Evidence(m.evidence().get(0).location(), EXTRACTOR, Certainty.EXTRACTED,
                                    "declares dependency " + dep))));
                } else if (target == null && ownGroups.contains(parts[0])) {
                    model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.WARNING, "module '" + m.name() + "' declares "
                            + "a dependency on " + dep + ", which looks like part of this project (same groupId) but was not analysed. "
                            + "Calls into it are outside the analysed code, so traces that pass through it will be PARTIAL. "
                            + "Add that module's folder (or the parent folder) to --source", m.evidence().get(0).location()));
                }
            }
        }
    }

    private static void putIfPresent(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    /** Files of a given kind that the model holds for analysis, as (display path, absolute path). */
    public static List<Entity> filesOfKind(AnalysisModel model, String kind) {
        return model.entitiesOfType(EntityType.FILE).stream()
                .filter(e -> kind.equals(e.attributes().get("kind"))).collect(Collectors.toList());
    }
}

package com.chiru.engine.syntax;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.model.*;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.*;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.tools.*;

/**
 * Evidence mining: the words the authors wrote next to the code. Javadoc on classes, methods and fields, and a block of {@code //} lines
 * directly above a declaration, are attached to the matching entity as {@code doc} (first sentences, tags removed, at most 300
 * characters). It is the only place where a business meaning can come from the source itself, so it is kept verbatim and never
 * interpreted. Run after {@code source-spoon}: it only adds an attribute to entities that exist. Matching is by file, name and the nearest
 * declaration line, since the parse-only tree has simple type names and the model has resolved ones.
 */
public final class DocEvidenceStage implements AnalysisStage {

    public static final String NAME = "evidence";
    static final int MAX = 300;
    private static final int NEAR = 4;

    @Override
    public String name() {
        return NAME;
    }

    private record Decl(String name, int line, String doc, String source) {}

    @Override
    public void run(StageContext ctx) throws IOException {
        AnalysisModel model = ctx.model();
        Map<Path, String> files = new LinkedHashMap<>();
        model.entitiesOfType(EntityType.FILE).stream()
                .filter(f -> "JAVA".equals(f.attributes().get("kind")) && "main".equals(f.attributes().get("scope")))
                .sorted(Comparator.comparing(Entity::name))
                .forEach(f -> files.put(Path.of(f.attributes().get("absolutePath")).toAbsolutePath().normalize(), f.name()));
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (files.isEmpty() || jc == null) {
            return;
        }
        Map<String, List<Decl>> byFile = new LinkedHashMap<>();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) jc.getTask(null, fm, diags, List.of("-proc:none", "-encoding", "UTF-8"), null,
                    fm.getJavaFileObjectsFromFiles(files.keySet().stream().map(Path::toFile).toList()));
            DocTrees trees = DocTrees.instance(task);
            for (CompilationUnitTree cu : task.parse()) {
                Path path = Path.of(cu.getSourceFile().toUri()).toAbsolutePath().normalize();
                String display = files.get(path);
                if (display != null) {
                    byFile.put(display, scan(cu, trees, path));
                }
            }
        }
        int attached = 0;
        for (Map.Entry<String, List<Decl>> e : byFile.entrySet()) {
            for (Decl d : e.getValue()) {
                Optional<Entity> target = match(model, e.getKey(), d);
                if (target.isPresent()) {
                    model.putEntity(new Entity(target.get().id(), target.get().name(), Map.of("doc", d.doc(), "docSource", d.source()), List.of()));
                    attached++;
                }
            }
        }
        model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO, "evidence mining: " + attached
                + " code comment(s) attached to classes, methods and fields (the only business wording the source itself contains)", null));
    }

    private static Optional<Entity> match(AnalysisModel model, String file, Decl d) {
        Entity best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (EntityType type : List.of(EntityType.CLASS, EntityType.METHOD, EntityType.FIELD)) {
            for (Entity e : model.entitiesOfType(type)) {
                SourceLocation loc = ModelIndex.location(e);
                if (loc == null || !file.equals(loc.file()) || !d.name().equals(e.name())) {
                    continue;
                }
                int distance = Math.abs(loc.startLine() - d.line());
                if (distance <= NEAR && distance < bestDistance) {
                    best = e;
                    bestDistance = distance;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private List<Decl> scan(CompilationUnitTree cu, DocTrees trees, Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Decl> out = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree t, Void v) {
                record(t, t.getSimpleName().toString());
                return super.visitClass(t, v);
            }

            @Override
            public Void visitMethod(MethodTree t, Void v) {
                String name = t.getName().toString();
                if (!name.equals("<init>")) {
                    record(t, name);
                }
                return super.visitMethod(t, v);
            }

            @Override
            public Void visitVariable(VariableTree t, Void v) {
                if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree) {
                    record(t, t.getName().toString());
                }
                return super.visitVariable(t, v);
            }

            private void record(Tree tree, String name) {
                TreePath here = getCurrentPath();
                long start = trees.getSourcePositions().getStartPosition(cu, tree);
                int line = (int) cu.getLineMap().getLineNumber(Math.max(0, start));
                String doc = javadoc(trees, here);
                String source = "javadoc";
                if (doc.isEmpty()) {
                    doc = lineComments(lines, line);
                    source = "comment";
                }
                if (!doc.isEmpty()) {
                    out.add(new Decl(name, line, doc, source));
                }
            }
        }.scan(cu, null);
        return out;
    }

    private static String javadoc(DocTrees trees, TreePath path) {
        DocCommentTree dc = trees.getDocCommentTree(path);
        if (dc == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        dc.getFirstSentence().forEach(x -> sb.append(x));
        dc.getBody().forEach(x -> sb.append(' ').append(x));
        return clean(sb.toString());
    }

    /** A contiguous block of {@code //} lines directly above the declaration. */
    static String lineComments(List<String> lines, int declarationLine) {
        Deque<String> block = new ArrayDeque<>();
        for (int i = declarationLine - 2; i >= 0; i--) {
            String t = lines.get(i).trim();
            if (t.startsWith("//")) {
                block.addFirst(t.substring(2).trim());
            } else if (!t.startsWith("@")) {
                break;
            }
        }
        return clean(String.join(" ", block));
    }

    static String clean(String raw) {
        String t = raw.replaceAll("<[^>]+>", " ").replaceAll("\\{@(?:code|link|linkplain)\\s+([^}]*)}", "$1")
                .replaceAll("\\s+", " ").trim();
        return t.length() <= MAX ? t : t.substring(0, MAX - 1) + "…";
    }
}

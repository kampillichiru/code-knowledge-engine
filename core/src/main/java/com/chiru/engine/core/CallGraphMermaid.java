package com.chiru.engine.core;

import com.chiru.engine.model.*;
import java.util.*;

/**
 * Renders the call edges of the analysis model as a Mermaid flowchart. Solid arrows are VERIFIED calls,
 * dashed arrows are PARTIAL (dynamic dispatch or target outside the analysed code), and a dashed arrow
 * labelled "unresolved" means the target could not be identified. The diagram shows what the static
 * analysis found; it is not a guaranteed runtime sequence.
 */
public final class CallGraphMermaid {

    private CallGraphMermaid() {}

    public static String render(AnalysisModel model, EntityId root, int maxDepth, boolean includeExternal) {
        Map<EntityId, String> nodes = new LinkedHashMap<>();
        List<String> edges = new ArrayList<>();
        Deque<EntityId> queue = new ArrayDeque<>();
        Map<EntityId, Integer> depth = new HashMap<>();
        queue.add(root);
        depth.put(root, 0);
        nodes.put(root, "n0");

        while (!queue.isEmpty()) {
            EntityId current = queue.poll();
            if (depth.get(current) >= maxDepth) {
                continue;
            }
            List<Relationship> out = new ArrayList<>(model.outgoing(current, RelationType.CALLS));
            out.sort(Comparator.comparing(r -> r.to().value()));
            for (Relationship r : out) {
                if (!includeExternal && isExternal(model, r.to())) {
                    continue;
                }
                String from = nodes.get(current);
                String to = nodes.computeIfAbsent(r.to(), k -> "n" + nodes.size());
                edges.add("    " + from + arrow(r.status()) + to);
                if (depth.putIfAbsent(r.to(), depth.get(current) + 1) == null) {
                    queue.add(r.to());
                }
            }
        }

        StringBuilder sb = new StringBuilder("flowchart TD\n");
        sb.append("    %% solid = verified call, dashed = partial (dynamic dispatch / external target)\n");
        nodes.forEach((id, n) -> sb.append("    ").append(n).append(nodeShape(model, id)).append('\n'));
        edges.forEach(e -> sb.append(e).append('\n'));
        return sb.toString();
    }

    private static String arrow(TraceStatus status) {
        return switch (status) {
            case VERIFIED -> " --> ";
            case PARTIAL -> " -.-> ";
            case UNRESOLVED -> " -.->|unresolved| ";
        };
    }

    private static boolean isExternal(AnalysisModel model, EntityId id) {
        return model.entity(id).map(e -> "true".equals(e.attributes().get("external"))).orElse(false);
    }

    private static String nodeShape(AnalysisModel model, EntityId id) {
        String label = label(id);
        return isExternal(model, id) ? "([\"" + label + "\"])" : "[\"" + label + "\"]";
    }

    /** {@code method:com.x.Foo#bar(int)} becomes {@code Foo.bar}. */
    static String label(EntityId id) {
        String key = id.value().substring(id.value().indexOf(':') + 1);
        int hash = key.indexOf('#');
        if (hash < 0) {
            return sanitize(key);
        }
        String owner = key.substring(0, hash);
        String member = key.substring(hash + 1, key.indexOf('(', hash));
        String simpleOwner = owner.substring(owner.lastIndexOf('.') + 1);
        member = member.replace("<init>", "constructor").replace("<clinit>", "static init");
        return sanitize(simpleOwner + "." + member);
    }

    private static String sanitize(String s) {
        return s.replace("\"", "'").replace("<", "#lt;").replace(">", "#gt;");
    }
}

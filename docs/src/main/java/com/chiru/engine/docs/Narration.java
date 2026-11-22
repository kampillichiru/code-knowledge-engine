package com.chiru.engine.docs;

import com.chiru.engine.core.Json;
import com.chiru.engine.model.AnalysisModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The hand-off to an AI (GitHub Copilot, Claude, any chat or agent) and the check of what comes back.
 *
 * <p><b>Out:</b> {@link #writePack} writes one small JSON file per section with the verified facts, each with an ID, and the rules the AI
 * must follow. A section is sized for one chat message, so the AI needs minimal context and cannot wander into the rest of the code.
 * <b>In:</b> the AI writes one reply file per section; {@link #importResponses} accepts a paragraph only if it cites facts of its section,
 * and every number, quoted value and capitalised label in it appears in the facts it cites, it contains no speculation and no code syntax.
 * Accepted paragraphs are written to {@code N01_AI_Narrative.md} marked as AI-worded; rejected ones are listed with the reason; facts the AI
 * did not mention are appended unchanged, so the AI can improve the wording but never remove or change a fact.
 */
public final class Narration {

    private Narration() {}

    record Fact(String id, String text) {}

    record Section(String id, String title, List<Fact> facts) {}

    /** What an import found. */
    public record Report(List<String> accepted, List<String> pending, int paragraphsAccepted, int paragraphsRejected,
                         Map<String, String> meanings, List<String> problems) {}

    static final String PROMPT = """
            # Instructions for the AI

            You are given one JSON file (a "pack") with a list of verified facts, each with an id. Write plain business prose from them for
            business analysts and subject matter experts, and save your answer as JSON in the `responses` folder under the same file name.

            Rules (an automatic checker enforces them; anything that breaks a rule is dropped):
            1. Use ONLY the facts in the pack. Do not add facts, numbers, names, reasons or purposes that are not in the pack.
            2. Every paragraph lists the ids of the facts it uses in `cites` (at least one). Copy ids exactly.
            3. Every number, every value in quotes and every ALL-CAPS label you write must appear in the facts you cite. Write numbers as digits, exactly as given.
            4. Do not guess. Words like probably, likely, might, perhaps, presumably, typically, usually, seems are not allowed.
            5. No code: no brackets like (), no ::, ->, ==, &&, no backticks. Use the wording of the facts.
            6. One idea per paragraph, at most 3 sentences. Say "when ... then ..." for decisions. Keep the order of the facts where it matters.
            7. If the pack is the `terms` section, do not write paragraphs. Instead propose, in `meanings`, a short business meaning (at most 25 words) ONLY for a
               term whose facts or code comment support one. If nothing supports a meaning, leave the term out. Do not invent.
            8. If the pack's section starts with `field-`, explain how that one output field gets its value, in this order: where the value comes from (table and column),
               each calculation step in order, what changes the value, how it is written to the file, then the worked examples (say they are illustrations) and the width check.
               Write the order in words ("First ... Then ... Finally ..."), not as a numbered list. Do not leave out a width problem or an untraceable source.
            9. Return only the JSON object, with no text around it.

            Reply format:
            {"section": "<same as the pack>", "paragraphs": [{"text": "...", "cites": ["id1", "id2"]}], "meanings": [{"term": "<key>", "meaning": "...", "cites": ["id"]}]}
            """;

    // ================================================================ out

    /** Writes {@code PROMPT.md} and one pack per section into {@code aiDir/pack}; creates {@code aiDir/responses}. Returns the pack files. */
    public static List<Path> writePack(AnalysisModel model, Path aiDir, Map<String, String> glossary, Map<String, String> answers) throws IOException {
        Files.createDirectories(aiDir.resolve("pack"));
        Files.createDirectories(aiDir.resolve("responses"));
        Files.writeString(aiDir.resolve("PROMPT.md"), PROMPT, StandardCharsets.UTF_8);
        List<Path> out = new ArrayList<>();
        for (Section s : sections(model, glossary, answers)) {
            ObjectNode root = Json.mapper().createObjectNode();
            root.put("section", s.id());
            root.put("task", s.title());
            ArrayNode rules = root.putArray("rules");
            for (String line : PROMPT.lines().filter(l -> l.matches("\\d\\. .*")).toList()) {
                rules.add(line);
            }
            ArrayNode facts = root.putArray("facts");
            for (Fact f : s.facts()) {
                facts.addObject().put("id", f.id()).put("text", f.text());
            }
            root.putObject("replyFormat").put("section", s.id()).putArray("paragraphs").addObject().put("text", "...").putArray("cites").add("<fact id>");
            Path file = aiDir.resolve("pack").resolve(fileName(s.id()) + ".json");
            Files.writeString(file, Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            out.add(file);
        }
        return out;
    }

    static List<Section> sections(AnalysisModel model, Map<String, String> glossary, Map<String, String> answers) {
        return FunctionalDocs.narrationSections(new DocContext(model, glossary, answers));
    }

    static String fileName(String sectionId) {
        return sectionId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ================================================================ in

    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d+(?:[.,]\\d+)*");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{1,80})\"|“([^”]{1,80})”");
    private static final Pattern CAPS = Pattern.compile("\\b[A-Z]{3,}\\b");
    private static final Set<String> ACRONYMS = Set.of("SQL", "JSON", "XML", "API", "CSV", "ZERO", "NOT", "AND", "THE");
    private static final List<String> SPECULATION = List.of("probably", "likely", "might", "perhaps", "presumably", "typically", "usually",
            "generally", "seems", "appears to", "may be", "could be", "i think", "i assume", "assuming");
    private static final List<String> CODE = List.of("()", "::", "->", "==", "&&", "||", "`");

    /** Checks and imports the replies in {@code aiDir/responses}; writes {@code N01_AI_Narrative.md} and {@code aiDir/verification-report.md}. */
    public static Report importResponses(AnalysisModel model, Path aiDir, Path outputDir, Map<String, String> glossary,
                                         Map<String, String> answers) throws IOException {
        List<Section> sections = sections(model, glossary, answers);
        StringBuilder doc = new StringBuilder("# N01 AI narrative\n\n_The functional story in smoother prose. Every paragraph was reworded by an AI from verified facts and "
                + "passed an automatic check (cited facts, numbers, values and labels all present, no guessing, no code). Where the AI said nothing, the facts are listed as they are._\n\n"
                + "> Generated from `analysis-model.json` (schema version " + AnalysisModel.SCHEMA_VERSION + "). Paragraphs are marked **AI-worded**; facts are EXTRACTED. "
                + "The deterministic documents F01 to F06 remain the reference.\n\n");
        StringBuilder fieldDoc = new StringBuilder("# N02 Field derivations\n\n_One section per output field, explained end to end by an AI from verified facts "
                + "(source column, calculation steps, what changes the value, how it is written, worked examples). Every paragraph passed the automatic check; "
                + "the examples are illustrative values worked through the formatting steps, not data from your system. Facts the AI did not reword are listed unchanged._\n\n");
        StringBuilder report = new StringBuilder("# AI verification report\n\n");
        List<String> accepted = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, String> meanings = new LinkedHashMap<>();
        int ok = 0;
        int bad = 0;
        for (Section s : sections) {
            Path reply = aiDir.resolve("responses").resolve(fileName(s.id()) + ".json");
            if (!Files.isRegularFile(reply)) {
                pending.add(s.id());
                continue;
            }
            JsonNode root;
            try {
                root = Json.mapper().readTree(stripFences(Files.readString(reply, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                problems.add(s.id() + ": the reply is not valid JSON (" + e.getMessage() + ")");
                pending.add(s.id());
                continue;
            }
            if (!s.id().equals(root.path("section").asText())) {
                problems.add(s.id() + ": the reply names section '" + root.path("section").asText() + "'");
                pending.add(s.id());
                continue;
            }
            Map<String, String> byId = s.facts().stream().collect(Collectors.toMap(Fact::id, Fact::text, (a, b) -> a, LinkedHashMap::new));
            Set<String> cited = new LinkedHashSet<>();
            StringBuilder target = s.id().startsWith("field-") ? fieldDoc : doc;
            target.append("## ").append(s.title()).append("\n\n");
            report.append("## ").append(s.id()).append("\n\n");
            int sectionOk = 0;
            for (JsonNode p : root.path("paragraphs")) {
                String text = p.path("text").asText("").trim();
                List<String> cites = new ArrayList<>();
                p.path("cites").forEach(n -> cites.add(n.asText()));
                List<String> why = check(text, cites, byId);
                if (why.isEmpty()) {
                    target.append(text).append("\n\n_AI-worded · grounded in: ").append(String.join(", ", cites)).append("_\n\n");
                    cited.addAll(cites);
                    ok++;
                    sectionOk++;
                } else {
                    bad++;
                    report.append("- REJECTED: \"").append(cut(text)).append("\" — ").append(String.join("; ", why)).append('\n');
                }
            }
            for (JsonNode m : root.path("meanings")) {
                String term = m.path("term").asText();
                String meaning = m.path("meaning").asText("").trim();
                List<String> cites = new ArrayList<>();
                m.path("cites").forEach(n -> cites.add(n.asText()));
                List<String> why = new ArrayList<>(check(meaning, cites, byId));
                if (!byId.containsKey("T:" + term)) {
                    why.add("the term is not in the pack");
                }
                if (meaning.split("\\s+").length > 30) {
                    why.add("longer than 25 words");
                }
                if (why.isEmpty()) {
                    meanings.put(term, meaning);
                } else {
                    report.append("- REJECTED meaning for ").append(term).append(": ").append(String.join("; ", why)).append('\n');
                }
            }
            List<Fact> rest = s.facts().stream().filter(f -> !cited.contains(f.id())).toList();
            if (!rest.isEmpty() && !s.id().equals("terms")) {
                target.append("**Also true (not reworded):**\n\n");
                rest.forEach(f -> target.append("- ").append(f.text()).append(" _(").append(f.id()).append(")_\n"));
                target.append('\n');
            }
            report.append("- ").append(sectionOk).append(" paragraph(s) accepted; ").append(rest.size()).append(" of ").append(s.facts().size())
                    .append(" fact(s) not cited (listed unchanged in N01).\n\n");
            accepted.add(s.id());
        }
        if (!pending.isEmpty()) {
            report.append("## Waiting for a reply\n\n").append(pending.stream().map(x -> "- " + x + "\n").collect(Collectors.joining())).append('\n');
        }
        problems.forEach(p -> report.append("- PROBLEM: ").append(p).append('\n'));
        Files.createDirectories(aiDir);
        Files.writeString(aiDir.resolve("verification-report.md"), report.toString(), StandardCharsets.UTF_8);
        if (accepted.stream().anyMatch(x -> !x.startsWith("field-"))) {
            Files.writeString(outputDir.resolve("N01_AI_Narrative.md"), doc.toString(), StandardCharsets.UTF_8);
        }
        if (accepted.stream().anyMatch(x -> x.startsWith("field-"))) {
            Files.writeString(outputDir.resolve("N02_Field_Derivations.md"), fieldDoc.toString(), StandardCharsets.UTF_8);
        }
        return new Report(accepted, pending, ok, bad, meanings, problems);
    }

    /** The reasons a paragraph is not accepted; empty when it is. */
    static List<String> check(String text, List<String> cites, Map<String, String> facts) {
        List<String> why = new ArrayList<>();
        if (text.length() < 15) {
            why.add("too short");
        }
        if (text.length() > 900) {
            why.add("too long");
        }
        if (cites.isEmpty()) {
            why.add("cites no fact");
        }
        for (String c : cites) {
            if (!facts.containsKey(c)) {
                why.add("cites unknown fact '" + c + "'");
            }
        }
        if (!why.isEmpty()) {
            return why;
        }
        String source = cites.stream().map(facts::get).collect(Collectors.joining(" "));
        String sourceNumbers = source.replace(",", "");
        Matcher n = NUMBER.matcher(text);
        while (n.find()) {
            String number = n.group().replace(",", "");
            if (!NUMBER_IN.matcher(sourceNumbers).results().anyMatch(r -> r.group().equals(number))) {
                why.add("the number " + n.group() + " is not in the cited facts");
            }
        }
        Matcher q = QUOTED.matcher(text);
        while (q.find()) {
            String value = q.group(1) != null ? q.group(1) : q.group(2);
            if (!source.contains(value)) {
                why.add("the value \"" + value + "\" is not in the cited facts");
            }
        }
        Matcher caps = CAPS.matcher(text);
        while (caps.find()) {
            if (!ACRONYMS.contains(caps.group()) && !source.contains(caps.group())) {
                why.add("the label " + caps.group() + " is not in the cited facts");
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : SPECULATION) {
            if (lower.matches("(?s).*\\b" + Pattern.quote(word) + "\\b.*")) {
                why.add("speculation (\"" + word + "\")");
            }
        }
        for (String code : CODE) {
            if (text.contains(code)) {
                why.add("code syntax (" + code + ")");
            }
        }
        return why.stream().distinct().toList();
    }

    private static final Pattern NUMBER_IN = Pattern.compile("(?<![\\w.])\\d+(?:\\.\\d+)*");

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        return t;
    }

    private static String cut(String s) {
        return s.length() <= 140 ? s : s.substring(0, 139) + "…";
    }
}

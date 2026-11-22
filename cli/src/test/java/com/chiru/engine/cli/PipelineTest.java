package com.chiru.engine.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole workflow: run, get the AI hand-off, verify replies, ask for missing information, answer, rebuild. */
class PipelineTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();

    private static int pipeline(Path out, String... extra) {
        String[] base = {"pipeline", "--source", SAMPLE.toString(), "--bytecode", SAMPLE.resolve("target/classes").toString(),
                "--output", out.toString(), "-q"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return Main.run(new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()), all);
    }

    private static String read(Path dir, String file) throws IOException {
        return Files.readString(dir.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void firstRunWritesEverythingAndAsksForWhatTheCodeCannotSay(@TempDir Path out) throws IOException {
        assertEquals(Main.OK, pipeline(out));
        for (String f : new String[] {"F01_Business_Overview.md", "F04_Data_Dictionary.md", "NEEDS_INPUT.md", "PIPELINE_STATUS.md",
                "ai/PROMPT.md", "ai/pack/overview.json", "ai/pack/scenario-ARCHIVE.json", "ai/pack/terms.json",
                "context/answers.template.properties", "context/glossary-worksheet.properties"}) {
            assertTrue(Files.exists(out.resolve(f)), f);
        }
        String needs = read(out, "NEEDS_INPUT.md");
        assertTrue(needs.contains("`source.adjustment`") && needs.contains("`system.purpose`") && needs.contains("`LARGE_QUANTITY`"), needs);
        assertFalse(Files.exists(out.resolve("N01_AI_Narrative.md")), "no reply yet, so no narrative");
        assertEquals(Main.NEEDS_INPUT, pipeline(out, "--require-complete"), "CI mode fails while information is missing");
    }

    @Test
    void answersAreUsedAndTheRebuildIsStable(@TempDir Path out) throws IOException {
        pipeline(out);
        Files.writeString(out.resolve("context/answers.properties"), "system.purpose = Month-end shipping.\n"
                + "source.adjustment = Set by Treasury in the ops file.\nnumber.BR-0007 = Annual to monthly rate.\nnumber.BR-0002 = Percent to fraction.\n");
        Files.writeString(out.resolve("context/glossary.properties"), "LARGE_QUANTITY = large-quantity threshold\nBASE_FEE = base fee\nFEE_CAP = fee cap\nSHIP_CAP = ship cap\n");
        assertEquals(Main.OK, pipeline(out, "--require-complete"));
        assertFalse(Files.exists(out.resolve("NEEDS_INPUT.md")), "everything answered");
        assertTrue(read(out, "F01_Business_Overview.md").contains("Month-end shipping."));
        assertTrue(read(out, "F04_Data_Dictionary.md").contains("Set by Treasury in the ops file."));
        String first = read(out, "F03_Business_Scenarios_And_Rules.md");
        pipeline(out);
        assertEquals(first, read(out, "F03_Business_Scenarios_And_Rules.md"), "running again changes nothing");
        assertTrue(read(out, "context/answers.properties").contains("Month-end shipping."), "the business file is never overwritten");
    }

    @Test
    void aReplyIsCheckedAndOnlyGroundedParagraphsAreKept(@TempDir Path out) throws IOException {
        pipeline(out);
        Files.writeString(out.resolve("ai/responses/scenario-ARCHIVE.json"), "```json\n{\"section\":\"scenario-ARCHIVE\",\"paragraphs\":["
                + "{\"text\":\"When the shipment quantity is zero, the status is \\\"ARCHIVED\\\" and the manifest record carries zero for the fee.\",\"cites\":[\"S:ARCHIVE:handle:P1\"]},"
                + "{\"text\":\"A rejected archive probably triggers a 30 day retry.\",\"cites\":[\"S:ARCHIVE:handle:P2\"]},"
                + "{\"text\":\"The status is \\\"BLOCKED\\\" when the quantity is not zero.\",\"cites\":[\"S:ARCHIVE:handle:P2\"]},"
                + "{\"text\":\"No citation here at all, just prose.\",\"cites\":[]},"
                + "{\"text\":\"Cites something that does not exist in this pack.\",\"cites\":[\"S:NOPE\"]}]}\n```\n");
        assertEquals(Main.OK, pipeline(out));
        String narrative = read(out, "N01_AI_Narrative.md");
        assertTrue(narrative.contains("the status is \"ARCHIVED\""), narrative);
        assertFalse(narrative.contains("30 day") || narrative.contains("BLOCKED"), "ungrounded paragraphs are dropped");
        String report = read(out, "ai/verification-report.md");
        assertTrue(report.contains("the number 30 is not in the cited facts") && report.contains("speculation"), report);
        assertTrue(report.contains("the value \"BLOCKED\" is not in the cited facts"), report);
        assertTrue(report.contains("cites no fact") && report.contains("cites unknown fact 'S:NOPE'"), report);
        assertTrue(narrative.contains("Also true (not reworded)"), "facts the AI did not use stay, unchanged");
    }

    @Test
    void everyFieldGetsItsOwnPackAndAGroundedReplyBecomesN02(@TempDir Path out) throws IOException {
        pipeline(out);
        String pack = read(out, "ai/pack/field-quantity.json");
        assertTrue(pack.contains("D:quantity:POS") && pack.contains("D:quantity:DISPATCH:STEP2") && pack.contains("D:quantity:EX1") && pack.contains("D:quantity:WIDTH"), pack);
        assertTrue(read(out, "ai/PROMPT.md").contains("starts with `field-`"));
        assertTrue(read(out, "PIPELINE_STATUS.md").contains("one field at a time"));
        Files.writeString(out.resolve("ai/responses/field-quantity.json"), "{\"section\":\"field-quantity\",\"paragraphs\":["
                + "{\"text\":\"For example, the value 5.00 is written to the file as 000000000000500, which is 15 characters. This is an illustration, not real data.\",\"cites\":[\"D:quantity:EX1\"]},"
                + "{\"text\":\"The value 777.00 is written as 000000000077700.\",\"cites\":[\"D:quantity:EX1\"]}]}");
        assertEquals(Main.OK, pipeline(out));
        String n02 = read(out, "N02_Field_Derivations.md");
        assertTrue(n02.contains("000000000000500, which is 15 characters"), n02);
        assertFalse(n02.contains("777.00"), "an invented example is dropped");
        assertTrue(read(out, "ai/verification-report.md").contains("the number 777.00 is not in the cited facts"));
        assertFalse(Files.exists(out.resolve("N01_AI_Narrative.md")), "the story sections have no reply");
    }

    @Test
    void aBrokenReplyIsReportedNotTrusted(@TempDir Path out) throws IOException {
        pipeline(out);
        Files.writeString(out.resolve("ai/responses/overview.json"), "this is not json");
        Files.writeString(out.resolve("ai/responses/open-questions.json"), "{\"section\":\"other\",\"paragraphs\":[]}");
        assertEquals(Main.OK, pipeline(out));
        String status = read(out, "PIPELINE_STATUS.md");
        assertTrue(status.contains("not valid JSON") && status.contains("names section 'other'"), status);
        assertFalse(Files.exists(out.resolve("N01_AI_Narrative.md")));
    }

    @Test
    void aProposedMeaningIsOnlyAWorksheetSuggestion(@TempDir Path out) throws IOException {
        pipeline(out);
        Files.writeString(out.resolve("ai/responses/terms.json"), "{\"section\":\"terms\",\"paragraphs\":[],\"meanings\":["
                + "{\"term\":\"FEE_CAP\",\"meaning\":\"Upper limit of the fee on one shipped.\",\"cites\":[\"T:FEE_CAP\"]},"
                + "{\"term\":\"BASE_FEE\",\"meaning\":\"Probably the 99 base fee.\",\"cites\":[\"T:BASE_FEE\"]}]}");
        pipeline(out);
        String worksheet = read(out, "context/glossary-worksheet.properties");
        assertTrue(worksheet.contains("AI-proposed (unconfirmed): Upper limit of the fee on one shipped."), worksheet);
        assertFalse(worksheet.contains("99 base fee"), "an ungrounded meaning is dropped");
        assertTrue(read(out, "NEEDS_INPUT.md").contains("`FEE_CAP`"), "it stays open until the business confirms it");
    }
}

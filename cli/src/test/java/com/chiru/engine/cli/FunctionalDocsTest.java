package com.chiru.engine.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The functional documents (F01 to F06): readable by a business analyst, faithful to the code, free of code syntax in the narrative. */
class FunctionalDocsTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static final List<String> F = List.of("F01_Business_Overview.md", "F02_End_To_End_Business_Flow.md",
            "F03_Business_Scenarios_And_Rules.md", "F04_Data_Dictionary.md", "F05_Glossary.md", "F06_Questions_For_Business_Experts.md", "F07_Field_Reference.md");
    private static Path out;

    private static int cli(String... args) {
        return Main.run(new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()), args);
    }

    private static String read(Path dir, String f) throws IOException {
        return Files.readString(dir.resolve(f), StandardCharsets.UTF_8);
    }

    @BeforeAll
    static void analyse() throws IOException {
        out = Files.createTempDirectory("ci-functional");
        assertEquals(Main.OK, cli("analyze", "--source", SAMPLE.toString(), "--bytecode", SAMPLE.resolve("target/classes").toString(),
                "--output", out.toString(), "--strict", "-q"));
    }

    @Test
    void allSixDocumentsAreWrittenAndIndexed() throws IOException {
        String index = read(out, "00_Index.md");
        for (String f : F) {
            assertTrue(Files.exists(out.resolve(f)), f);
            assertTrue(index.contains(f), "the index lists " + f);
        }
    }

    @Test
    void theOverviewTellsTheStoryInBusinessWords() throws IOException {
        String s = read(out, "F01_Business_Overview.md");
        assertTrue(s.contains("It reads records from the shipment table"), s);
        assertTrue(s.contains("**ARCHIVE**, **DISPATCH**, **STORAGE**"), s);
        assertTrue(s.contains("shipment ID, status, quantity, fee, adjustment"));
        assertTrue(s.contains("due date is on or before the value supplied at run time"), "the SQL filter is in words");
    }

    @Test
    void theFlowHasADiagramAndNumberedSteps() throws IOException {
        String s = read(out, "F02_End_To_End_Business_Flow.md");
        assertTrue(s.contains("```mermaid\nflowchart TD"));
        assertTrue(s.contains("route{\"Which kind of work?\"}"));
        assertTrue(s.contains("1. **Start.**") && s.contains("5. **Write the output.**") && s.contains("6. **Save the result.**"), s);
    }

    @Test
    void archiveRulesAreStatedWithTheirConditions() throws IOException {
        String s = read(out, "F03_Business_Scenarios_And_Rules.md");
        assertTrue(s.contains("| 1 | shipment quantity is zero | no other change | returns a new manifest record with shipment ID, \"ARCHIVED\""), s);
        assertTrue(s.contains("| 2 | shipment quantity is not zero | no other change | returns a new manifest record with shipment ID, \"REJECTED\""), s);
        assertTrue(s.contains("shipment due date is before today's date and shipment quantity is greater than the large quantity"));
        assertTrue(s.contains("sets surcharge to (surcharge × 1.5)"), "the late-shipped multiplier is a calculation in words");
    }

    @Test
    void theDataDictionaryExplainsEachFieldPerKindOfWork() throws IOException {
        String s = read(out, "F04_Data_Dictionary.md");
        assertTrue(s.contains("| shipment ID | STORAGE | `shipment.id` | copied | VERIFIED |"), s);
        assertTrue(s.contains("| quantity | STORAGE | `shipment.quantity`, `shipment.rate_pct` | calculated |"), s);
        assertTrue(s.contains("| fee | ARCHIVE | zero | fixed value |"), "the same fixed value under both conditions is one value");
        assertTrue(s.contains("**ARCHIVE**\n\n- **Fixed value:** \"ARCHIVED\" when shipment quantity is zero; \"REJECTED\" when shipment quantity is not zero"), s);
        assertTrue(s.contains("Supplied") || s.contains("supplied when the job runs"));
        assertTrue(s.contains("confirm with the business"), "the unsourced adjustment is flagged");
    }

    @Test
    void theLineageCardNamesTableColumnTypeAndQuery() throws IOException {
        String s = read(out, "F04_Data_Dictionary.md");
        assertTrue(s.contains("shipment quantity — table `shipment`, column `quantity` (DECIMAL (15, 2))"), s);
        assertTrue(s.contains("- **Read by:** Records read from the shipment table where status is one of 'DISPATCH', 'STORAGE', 'ARCHIVE'"), s);
        assertTrue(s.contains("- **Written to:** the manifest file, characters 1 to 10 (10 wide)"), "the file is named in words");
        assertTrue(s.contains("- **Layout:** right-aligned, padded with '0' on the left to 10 characters"), s);
    }

    @Test
    void calculationsAreNumberedInExecutionOrderWithNamedIntermediates() throws IOException {
        String s = read(out, "F04_Data_Dictionary.md");
        int surcharge = s.indexOf("1. surcharge = ((shipment quantity × shipment rate percent) ÷ 1200");
        int newQuantity = s.indexOf("2. new quantity = (shipment quantity + surcharge)");
        assertTrue(surcharge > 0 && newQuantity > surcharge, "surcharge is calculated before the new quantity that uses it");
        assertTrue(s.contains("The field takes the value of **new quantity**."));
        // the called helper runs before the statement that uses its result
        int helper = s.indexOf("2. fee = (the base fee + ((amount × rate percent) ÷ 100");
        int call = s.indexOf("3. fee = fee for (shipped, shipment rate percent) of fee calculator (only when shipped is positive)");
        assertTrue(helper > 0 && call > helper, "the fee calculation inside the helper is listed before the call that uses it");
    }

    @Test
    void aConditionIsNotShownAsASourceOfTheValue() throws IOException {
        String s = read(out, "F04_Data_Dictionary.md");
        assertTrue(s.contains("| status | DISPATCH | one of \"SENT\" / \"PARTIAL\" otherwise | chosen by condition |"), s);
        assertTrue(s.contains("- **The value is chosen by a condition:** \"SENT\" when new quantity is zero; \"PARTIAL\" otherwise"), s);
        assertTrue(s.contains("- **The choice depends on:** shipment quantity — table `shipment`, column `quantity`"), s);
    }

    @Test
    void theFieldReferenceTracesBothWaysAndNamesWhatChangesAField() throws IOException {
        String s = read(out, "F07_Field_Reference.md");
        assertTrue(s.contains("| [shipment ID](#f-shipmentid) | 1–10 | ARCHIVE, DISPATCH, STORAGE | nothing (same rule every time) | `shipment.id` |"), s);
        assertTrue(s.contains("| [status](#f-status) | 11–18 | ARCHIVE, DISPATCH, STORAGE | quantity, new quantity |"), s);
        assertTrue(s.contains("| shipped | 2 | quantity, fee | shipped is positive |"), "what changes which fields");
        assertTrue(s.contains("| `shipment.rate_pct` | DECIMAL (5, 2) | [quantity](#f-quantity) (DISPATCH)"), "top to bottom: column to fields");
        assertTrue(s.contains("- **Path back (DISPATCH):** **quantity** ← new quantity ← fee ← shipped ← `shipment.quantity`, `shipment.rate_pct`"), s);
        assertTrue(s.contains("<a id=\"f-quantity\"></a>"), "every field is a link target");
        assertFalse(s.contains("ShippingJob") || s.contains("ManifestWriter"));
    }

    @Test
    void aFormattingHelperIsExpandedIntoStepsWithWorkedExamplesAndAWidthCheck() throws IOException {
        String s = read(out, "F07_Field_Reference.md");
        assertTrue(s.contains("  2. Move the decimal point 2 places to the right (multiply by 100)\n  3. Drop everything after the decimal point (no rounding)\n"
                + "  4. Convert the value to text\n  5. Add '0' on the left until the text is 15 characters long"), s);
        assertTrue(s.contains("| 1234.56 | 123456 → 123456 → \"123456\" → \"000000000123456\" | `000000000123456` | 15 |"), "worked example");
        assertTrue(s.contains("- **Layout:** right-aligned, padded with '0' on the left to 15 characters"), "alignment comes from the helper's body");
        assertTrue(s.contains("**Width check:** Fits: the largest value the column allows is written as 15 characters, within the 15 available."), s);
        assertTrue(s.contains("## Width problems") && s.contains("| [fee](#f-fee) | 10 |"), "a 15-character result in a 10-wide field is flagged");
    }

    @Test
    void technicalNamesStayOutOfTheBusinessDocuments() throws IOException {
        for (String f : List.of("F01_Business_Overview.md", "F02_End_To_End_Business_Flow.md", "F03_Business_Scenarios_And_Rules.md", "F04_Data_Dictionary.md", "F07_Field_Reference.md")) {
            String t = read(out, f);
            assertFalse(t.contains("ShippingJob") || t.contains("ManifestWriter"), f + " names a class");
        }
        assertTrue(read(out, "F02_End_To_End_Business_Flow.md").contains("1. **Start.** The shipping job begins the run."));
    }

    @Test
    void theNarrativeContainsNoJavaSyntax() throws IOException {
        for (String f : F) {
            String text = read(out, f).replaceAll("(?s)```.*?```", "").replaceAll("`[^`]*`", "");
            for (String leak : List.of("getQuantity", "signum()", "BigDecimal", "java.math", "com.sample", ".compareTo", " == ", " && ", " || ", "::")) {
                assertFalse(text.contains(leak), f + " leaks '" + leak + "'");
            }
        }
    }

    @Test
    void everyMermaidBlockIsClosedAndStartsWithAFlowchart() throws IOException {
        Pattern block = Pattern.compile("```mermaid\n(.*?)```", Pattern.DOTALL);
        for (String f : F) {
            String s = read(out, f);
            Matcher m = block.matcher(s);
            int blocks = 0;
            while (m.find()) {
                blocks++;
                assertTrue(m.group(1).startsWith("flowchart TD"), f);
                assertEquals(m.group(1).chars().filter(c -> c == '[').count(), m.group(1).chars().filter(c -> c == ']').count(), "balanced brackets in " + f);
            }
            assertEquals(s.split("```mermaid", -1).length - 1, blocks, "every mermaid fence is closed in " + f);
        }
    }

    @Test
    void theQuestionsAskAboutWhatTheCodeCannotSay() throws IOException {
        String s = read(out, "F06_Questions_For_Business_Experts.md");
        assertTrue(s.contains("the system setting \"rate.adjustment.\" followed by shipment ID"), s);
        assertTrue(s.contains("fee cap = 25.00"), s);
        assertTrue(s.contains("No business names were supplied"));
    }

    @Test
    void aGlossaryFileChangesTheWordingEverywhereOnRegeneration() throws IOException {
        Path copy = Files.createTempDirectory("ci-glossary");
        Files.copy(out.resolve("analysis-model.json"), copy.resolve("analysis-model.json"));
        Files.writeString(copy.resolve("glossary.properties"), "quantity = outstanding quantity\nratePct = annual surcharge rate\n");
        assertEquals(Main.OK, cli("docs", "--model-dir", copy.toString()));
        String s = read(copy, "F03_Business_Scenarios_And_Rules.md");
        assertTrue(s.contains("shipment outstanding quantity is zero"), s);
        assertFalse(read(copy, "F06_Questions_For_Business_Experts.md").contains("No business names were supplied"));
        assertEquals(read(out, "F01_Business_Overview.md").length() > 0, true);
    }

    @Test
    void theDocumentsAreDeterministic() throws IOException {
        Path again = Files.createTempDirectory("ci-functional2");
        Files.copy(out.resolve("analysis-model.json"), again.resolve("analysis-model.json"));
        assertEquals(Main.OK, cli("docs", "--model-dir", again.toString()));
        for (String f : F) {
            assertEquals(read(out, f), read(again, f), f);
        }
    }
}

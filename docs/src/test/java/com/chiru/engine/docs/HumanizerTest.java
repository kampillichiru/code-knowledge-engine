package com.chiru.engine.docs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HumanizerTest {

    private final Humanizer h = new Humanizer(Map.of());

    @Test
    void namesBecomeWordsAndAbbreviationsAreSpelledOut() {
        assertEquals("new quantity", h.words("newQuantity"));
        assertEquals("rate percent", h.words("rate_pct"));
        assertEquals("ship cap", h.words("SHIP_CAP"));
        assertEquals("shipment ID", h.words("shipmentId"));
    }

    @Test
    void zeroChecksReadAsEnglish() {
        assertEquals("shipment quantity is zero", h.condition("shipment.getQuantity().signum() == 0"));
        assertEquals("shipment quantity is not zero", h.condition("not (shipment.getQuantity().signum() == 0)"));
        assertEquals("shipped is positive", h.condition("shipped.signum() > 0"));
        assertEquals("shipped is zero or negative", h.condition("not (shipped.signum() > 0)"));
    }

    @Test
    void comparisonsAndLogicReadAsEnglish() {
        assertEquals("shipment status is \"DISPATCH\"", h.condition("shipment.getStatus() == \"DISPATCH\""));
        assertEquals("amount is at least limit and status is not \"X\"", h.condition("amount >= limit && status != \"X\""));
        assertEquals("amount is less than limit", h.condition("not (amount >= limit)"));
        assertEquals("name is missing", h.condition("name == null"));
        assertEquals("a is greater than b", h.condition("a.compareTo(b) > 0"));
        assertEquals("a does not equal b", h.condition("!a.equals(b)"));
        assertEquals("always", h.condition(""));
    }

    @Test
    void calculationsReadAsArithmetic() {
        assertEquals("the smaller of shipment quantity and the ship cap",
                h.value("shipment.getQuantity().min(com.sample.shipping.DispatchService.SHIP_CAP)"));
        assertEquals("((shipment quantity × shipment rate percent) ÷ 1200, to 2 decimal places, rounding half up)",
                h.value("shipment.getQuantity().multiply(shipment.getRatePct()).divide(new java.math.BigDecimal(\"1200\"), 2, java.math.RoundingMode.HALF_UP)"));
        assertEquals("zero", h.value("java.math.BigDecimal.ZERO"));
        assertEquals("(a + b)", h.value("a.add(b)"));
    }

    @Test
    void effectsReadAsActions() {
        assertEquals("sets status to \"SENT\"", h.effect("status = \"SENT\"   [in: (x) ? \"SENT\" : \"PARTIAL\"]"));
        assertEquals("returns a new manifest record with shipment ID, \"ARCHIVED\", zero",
                h.effect("return new com.sample.shipping.ManifestRecord(shipment.getId(), \"ARCHIVED\", java.math.BigDecimal.ZERO)"));
        assertEquals("increases total by amount", h.effect("total (compound) += amount"));
        assertEquals("in every case, performs close (connection)", h.effect("(finally) close(connection)"));
    }

    @Test
    void whatCannotBeParsedIsShownAsCodeNotDropped() {
        assertEquals("`x -> y -> z @@`", h.value("x -> y -> z @@"));
        assertEquals(1, h.unparsed());
    }

    @Test
    void theGlossaryOverridesNames() {
        Humanizer g = new Humanizer(Map.of("ratePct", "annual surcharge rate", "shipment.rate_pct", "surcharge rate"));
        assertEquals("shipment annual surcharge rate is positive", g.condition("shipment.getRatePct().signum() > 0"));
        assertEquals("surcharge rate", g.column("shipment", "rate_pct"));
    }
}

package com.sample.shipping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** STORAGE scenario: monthly surcharge, with a penalty when overdue. */
public class StorageService implements ScenarioHandler {
    private static final BigDecimal LARGE_QUANTITY = new BigDecimal("1000");
    private final ExternalRateClient rateClient = new ExternalRateClient();

    @Override
    public ManifestRecord handle(Shipment shipment) {
        BigDecimal surcharge = shipment.getQuantity().multiply(shipment.getRatePct())
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_UP);
        if (shipment.getDueDate().isBefore(LocalDate.now())) {
            if (shipment.getQuantity().compareTo(LARGE_QUANTITY) > 0) {
                surcharge = surcharge.multiply(new BigDecimal("1.5"));
            } else {
                surcharge = surcharge.add(new BigDecimal("1.00"));
            }
        }
        BigDecimal newQuantity = shipment.getQuantity().add(surcharge);
        BigDecimal adjustment = rateClient.fetchAdjustment(shipment.getId());
        return new ManifestRecord(shipment.getId(), "STORED", newQuantity, surcharge, adjustment);
    }
}

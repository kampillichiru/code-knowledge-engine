package com.sample.shipping;

import java.math.BigDecimal;

/** DISPATCH scenario: ship a capped quantity and charge a fee. */
public class DispatchService implements ScenarioHandler {
    private static final BigDecimal SHIP_CAP = new BigDecimal("500.00");
    private final FeeCalculator feeCalculator = new FeeCalculator();

    @Override
    public ManifestRecord handle(Shipment shipment) {
        BigDecimal shipped = shipment.getQuantity().min(SHIP_CAP);
        BigDecimal newQuantity = shipment.getQuantity().subtract(shipped);
        String status = newQuantity.signum() == 0 ? "SENT" : "PARTIAL";
        BigDecimal fee = BigDecimal.ZERO;
        if (shipped.signum() > 0) {
            fee = feeCalculator.feeFor(shipped, shipment.getRatePct());
            newQuantity = newQuantity.add(fee);
        }
        return new ManifestRecord(shipment.getId(), status, newQuantity, fee, BigDecimal.ZERO);
    }
}

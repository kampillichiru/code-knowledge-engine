package com.sample.shipping;

import java.math.BigDecimal;

/** ARCHIVE scenario: an shipment closes only when its quantity is zero. */
public class ArchiveService implements ScenarioHandler {

    @Override
    public ManifestRecord handle(Shipment shipment) {
        if (shipment.getQuantity().signum() == 0) {
            return new ManifestRecord(shipment.getId(), "ARCHIVED", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return new ManifestRecord(shipment.getId(), "REJECTED", shipment.getQuantity(), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Not called from anywhere: exercises unreachable-method reporting. */
    String legacyReason(Shipment shipment) {
        return "quantity " + shipment.getQuantity();
    }
}

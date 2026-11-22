package com.sample.shipping;

import java.math.BigDecimal;

/** One line of the output manifest file. */
public class ManifestRecord {
    private final long shipmentId;
    private final String status;
    private final BigDecimal quantity;
    private final BigDecimal fee;
    private final BigDecimal adjustment;

    public ManifestRecord(long shipmentId, String status, BigDecimal quantity, BigDecimal fee, BigDecimal adjustment) {
        this.shipmentId = shipmentId;
        this.status = status;
        this.quantity = quantity;
        this.fee = fee;
        this.adjustment = adjustment;
    }

    public long getShipmentId() { return shipmentId; }
    public String getStatus() { return status; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getAdjustment() { return adjustment; }
}

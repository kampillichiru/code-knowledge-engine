package com.sample.shipping;

import java.math.BigDecimal;

/** Value comes from a runtime system property, so static lineage stops here (unresolved). */
public class ExternalRateClient {
    public BigDecimal fetchAdjustment(long shipmentId) {
        return new BigDecimal(System.getProperty("rate.adjustment." + shipmentId, "0.00"));
    }
}

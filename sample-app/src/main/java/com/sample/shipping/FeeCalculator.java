package com.sample.shipping;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class FeeCalculator {
    private static final BigDecimal BASE_FEE = new BigDecimal("2.50");
    private static final BigDecimal FEE_CAP = new BigDecimal("25.00");

    /** fee = BASE_FEE + amount * ratePct / 100, capped at FEE_CAP. */
    public BigDecimal feeFor(BigDecimal amount, BigDecimal ratePct) {
        BigDecimal fee = BASE_FEE.add(amount.multiply(ratePct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
        if (fee.compareTo(FEE_CAP) > 0) {
            fee = FEE_CAP;
        }
        return fee;
    }
}

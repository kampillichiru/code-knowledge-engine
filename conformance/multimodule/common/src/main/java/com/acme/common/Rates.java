package com.acme.common;

import java.math.BigDecimal;

/** Shared pricing rules, used by other modules. */
public class Rates {
    public static final BigDecimal BASE = new BigDecimal("0.05");
    public static final BigDecimal VIP_BASE = new BigDecimal("0.03");

    public static BigDecimal rateFor(boolean vip) {
        if (vip) {
            return VIP_BASE;
        }
        return BASE;
    }

    public static BigDecimal surcharge(BigDecimal amount, boolean vip) {
        return amount.multiply(rateFor(vip));
    }
}

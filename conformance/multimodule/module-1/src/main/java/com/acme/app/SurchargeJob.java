package com.acme.app;

import com.acme.common.Rates;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes one fixed-width line per shipment: id (10) and surcharge (12). The surcharge comes from the common module. */
public class SurchargeJob {

    public static void main(String[] args) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(format(42L, new BigDecimal("1000"), args.length > 1));
        Files.write(Path.of(args[0]), lines);
    }

    static String format(long id, BigDecimal quantity, boolean vip) {
        BigDecimal surcharge = Rates.surcharge(quantity, vip);
        return pad(String.valueOf(id), 10) + pad(surcharge.toPlainString(), 12);
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }
}

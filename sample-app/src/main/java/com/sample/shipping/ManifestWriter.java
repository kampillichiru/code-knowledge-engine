package com.sample.shipping;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-width manifest layout (65 chars):
 * shipmentId 1-10 (zero padded), status 11-18, quantity 19-33, fee 34-43, adjustment 44-53.
 */
public class ManifestWriter {

    public String format(ManifestRecord r) {
        return padLeft(String.valueOf(r.getShipmentId()), 10, '0')
                + padRight(r.getStatus(), 8)
                + amount(r.getQuantity(), 15)
                + amount(r.getFee(), 10)
                + amount(r.getAdjustment(), 10);
    }

    public void write(Path file, List<ManifestRecord> records) throws IOException {
        List<String> lines = new ArrayList<>();
        for (ManifestRecord r : records) {
            lines.add(format(r));
        }
        Files.write(file, lines);
    }

    private static String amount(BigDecimal value, int width) {
        long cents = value.movePointRight(2).longValue();
        return padLeft(Long.toString(cents), width, '0');
    }

    private static String padLeft(String s, int width, char pad) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < width; i++) {
            sb.append(pad);
        }
        return sb.append(s).toString();
    }

    private static String padRight(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}

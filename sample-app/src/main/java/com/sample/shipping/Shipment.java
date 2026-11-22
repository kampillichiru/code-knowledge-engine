package com.sample.shipping;

import java.math.BigDecimal;
import java.time.LocalDate;

public class Shipment {
    private final long id;
    private final String status;
    private final BigDecimal quantity;
    private final BigDecimal ratePct;
    private final LocalDate dueDate;

    public Shipment(long id, String status, BigDecimal quantity, BigDecimal ratePct, LocalDate dueDate) {
        this.id = id;
        this.status = status;
        this.quantity = quantity;
        this.ratePct = ratePct;
        this.dueDate = dueDate;
    }

    public long getId() { return id; }
    public String getStatus() { return status; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getRatePct() { return ratePct; }
    public LocalDate getDueDate() { return dueDate; }
}

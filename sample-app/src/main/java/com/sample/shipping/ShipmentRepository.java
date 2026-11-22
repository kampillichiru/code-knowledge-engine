package com.sample.shipping;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ShipmentRepository {
    private static final String SELECT_DUE =
            "SELECT a.id, a.status, a.quantity, a.rate_pct, a.due_date "
            + "FROM shipment a WHERE a.status IN ('DISPATCH','STORAGE','ARCHIVE') AND a.due_date <= ?";
    private static final String UPDATE_SHIPMENT =
            "UPDATE shipment SET quantity = ?, status = ? WHERE id = ?";

    private final Connection connection;

    public ShipmentRepository(Connection connection) {
        this.connection = connection;
    }

    public List<Shipment> findDue(LocalDate asOf) throws SQLException {
        List<Shipment> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_DUE)) {
            ps.setDate(1, java.sql.Date.valueOf(asOf));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Shipment(rs.getLong("id"), rs.getString("status"),
                            rs.getBigDecimal("quantity"), rs.getBigDecimal("rate_pct"),
                            rs.getDate("due_date").toLocalDate()));
                }
            }
        }
        return result;
    }

    public void update(ManifestRecord record) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_SHIPMENT)) {
            ps.setBigDecimal(1, record.getQuantity());
            ps.setString(2, record.getStatus());
            ps.setLong(3, record.getShipmentId());
            ps.executeUpdate();
        }
    }
}

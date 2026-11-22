package com.sample.shipping;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Batch entry point: read due shipments, route each to its scenario, update and write the manifest. */
public class ShippingJob {

    public static void main(String[] args) throws Exception {
        try (Connection connection = DriverManager.getConnection(args[0])) {
            ShipmentRepository repository = new ShipmentRepository(connection);
            ScenarioRouter router = new ScenarioRouter();
            List<ManifestRecord> records = new ArrayList<>();
            for (Shipment shipment : repository.findDue(LocalDate.now())) {
                ManifestRecord record = router.route(shipment);
                repository.update(record);
                records.add(record);
            }
            new ManifestWriter().write(Path.of(args[1]), records);
        }
    }
}

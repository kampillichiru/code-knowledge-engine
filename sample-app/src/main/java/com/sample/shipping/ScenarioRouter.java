package com.sample.shipping;

public class ScenarioRouter {
    private final ScenarioHandler shipped = new DispatchService();
    private final ScenarioHandler storage = new StorageService();
    private final ScenarioHandler archive = new ArchiveService();

    public ManifestRecord route(Shipment shipment) {
        switch (shipment.getStatus()) {
            case "DISPATCH":
                return shipped.handle(shipment);
            case "STORAGE":
                return storage.handle(shipment);
            case "ARCHIVE":
                return archive.handle(shipment);
            default:
                throw new IllegalArgumentException("Unknown status: " + shipment.getStatus());
        }
    }
}

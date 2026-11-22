package com.sample.shipping;

public interface ScenarioHandler {
    ManifestRecord handle(Shipment shipment);
}

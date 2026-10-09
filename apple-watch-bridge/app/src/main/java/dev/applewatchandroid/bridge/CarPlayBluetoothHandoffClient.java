package dev.applewatchandroid.bridge;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;

/**
 * Signature-protected client for CatPlay's Bluetooth-bootstrap handoff.
 * Responses contain state booleans only; no BMW or network identity crosses
 * this boundary.
 */
final class CarPlayBluetoothHandoffClient {
    private static final Uri URI =
            Uri.parse(
                    "content://io.catplay.android.bluetooth-handoff");
    private static final int PROTOCOL_VERSION = 1;

    private final ContentResolver resolver;

    CarPlayBluetoothHandoffClient(
            ContentResolver resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException(
                    "Content resolver is required");
        }
        this.resolver = resolver;
    }

    Status status() {
        return call("status");
    }

    Status prepare() {
        return call("prepare");
    }

    Status resume() {
        return call("resume");
    }

    private Status call(
            String method) {
        Bundle response;
        try {
            response = resolver.call(
                    URI,
                    method,
                    null,
                    null);
        } catch (IllegalArgumentException
                | SecurityException error) {
            return Status.unavailable();
        }
        if (response == null
                || response.getInt(
                "protocolVersion",
                -1) != PROTOCOL_VERSION) {
            return Status.unavailable();
        }
        return new Status(
                true,
                response.getBoolean(
                        "wifiReady",
                        false),
                response.getBoolean(
                        "vehicleMediaActive",
                        false),
                response.getBoolean(
                        "ready",
                        false),
                response.getBoolean(
                        "prepared",
                        false));
    }

    static final class Status {
        final boolean available;
        final boolean wifiReady;
        final boolean vehicleMediaActive;
        final boolean ready;
        final boolean prepared;

        Status(
                boolean available,
                boolean wifiReady,
                boolean vehicleMediaActive,
                boolean ready,
                boolean prepared) {
            this.available = available;
            this.wifiReady = wifiReady;
            this.vehicleMediaActive = vehicleMediaActive;
            this.ready = ready;
            this.prepared = prepared;
        }

        static Status unavailable() {
            return new Status(
                    false,
                    false,
                    false,
                    false,
                    false);
        }
    }
}

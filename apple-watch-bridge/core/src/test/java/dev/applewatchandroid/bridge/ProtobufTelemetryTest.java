package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtobufTelemetryTest {

    @Test
    public void describesVarintAndString() {
        // field1 varint 1 ; field2 string "SIM"
        byte[] payload = new byte[]{0x08, 0x01, 0x12, 0x03, 'S', 'I', 'M'};
        String description = ProtobufTelemetry.describe(payload);
        assertTrue(description.contains("f1=varint(1)"));
        assertTrue(description.contains("f2=string(\"SIM\")"));
    }

    @Test
    public void describesNestedMessage() {
        // field1 = len-delim {field1 varint 7}
        byte[] payload = new byte[]{0x0A, 0x02, 0x08, 0x07};
        String description = ProtobufTelemetry.describe(payload);
        assertTrue(description.contains("f1=msg{f1=varint(7)}"));
    }

    @Test
    public void opaqueBytesAsLength() {
        byte[] payload = new byte[]{0x0A, 0x03, 0x01, 0x02, 0x03};
        String description = ProtobufTelemetry.describe(payload);
        assertTrue(description.contains("f1=bytes(3)"));
    }

    @Test
    public void handlesEmptyAndAbsent() {
        assertEquals("absent", ProtobufTelemetry.describe(null));
        assertEquals("empty", ProtobufTelemetry.describe(new byte[0]));
    }

    @Test
    public void stopsOnTruncatedVarint() {
        byte[] payload = new byte[]{(byte) 0x88};
        // The truncated tag consumes its byte; no fields are rendered.
        assertEquals("fields=0 {}", ProtobufTelemetry.describe(payload));
    }
}

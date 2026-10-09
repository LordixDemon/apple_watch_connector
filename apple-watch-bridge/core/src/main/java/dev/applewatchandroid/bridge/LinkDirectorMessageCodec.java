package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;

/** watchOS 23S303 NRLinkDirectorMessage v2, carried in IKE notify C60E.
 * Hello and the actually established BT_CL Scalable transport only.
 * These announcements never assert NanoRegistry pairing/setup completion.
 */
final class LinkDirectorMessageCodec {
    static final int HELLO = 1;
    static final int DEVICE_LINK_STATE = 6;
    static final int BLUETOOTH_SCALABLE = 1; // native link subtype 120; direct L2CAP 121 would encode 8

    static byte[] encode(long identifier, int type) {
        if (identifier <= 0 || (type != HELLO && type != DEVICE_LINK_STATE)) {
            throw new IllegalArgumentException("Invalid LinkDirector announcement");
        }
        int valueLength = type == HELLO ? 0 : 1;
        ByteBuffer data = ByteBuffer.allocate(19 + valueLength);
        data.put((byte) 2).put((byte) 0).putShort((short) (3 + valueLength));
        data.putInt(0).putLong(identifier).put((byte) type).putShort((short) valueLength);
        if (valueLength != 0) data.put((byte) BLUETOOTH_SCALABLE);
        return data.array();
    }

    static byte[] applicationServiceRequest(long identifier, byte[] value) {
        if (identifier <= 0 || value == null || value.length == 0 || value.length > 1200)
            throw new IllegalArgumentException("Invalid application service discovery announcement");
        return ByteBuffer.allocate(19 + value.length).put((byte) 2).put((byte) 0)
                .putShort((short) (3 + value.length)).putInt(0).putLong(identifier)
                .put((byte) 20).putShort((short) value.length).put(value).array();
    }

    /** Native sequence seed: boot epoch microseconds + half continuous uptime. */
    static long sequenceSeed(long epochMillis, long elapsedNanos) {
        long seed = Math.subtractExact(Math.multiplyExact(epochMillis, 1000L), elapsedNanos / 2000L);
        if (seed <= 0) throw new IllegalArgumentException("Invalid LinkDirector sequence clock");
        return seed;
    }

    static String name(int type) { return type == HELLO ? "Hello" : "DeviceLinkState(BluetoothScalable)"; }
}

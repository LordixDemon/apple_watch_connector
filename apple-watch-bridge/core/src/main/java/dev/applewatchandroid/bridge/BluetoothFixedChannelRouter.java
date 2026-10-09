package dev.applewatchandroid.bridge;

/** Separates ATT/LE signaling from ACL without consuming IKE, SMP or BT_CL fragments. */
final class BluetoothFixedChannelRouter {
    private final HciCodec.AclReassembler fixed = new HciCodec.AclReassembler();
    private int partialHandle = -1;

    @FunctionalInterface interface Receiver {
        void receive(HciCodec.L2capPdu packet) throws Exception;
    }

    boolean accept(byte[] acl, int activeHandle, Receiver receiver) throws Exception {
        if (acl == null || acl.length < 4) return false;
        int header = u16(acl, 0);
        int handle = header & 0xfff;
        int boundary = (header >>> 12) & 3;
        if (handle != activeHandle || u16(acl, 2) != acl.length - 4) return false;
        if (boundary == 0 || boundary == 2) {
            if (acl.length < 8) return false;
            reset();
            int cid = u16(acl, 6);
            if (cid != BluetoothLinkMaintenance.ATT_CID && cid != BluetoothLinkMaintenance.LE_SIGNALING_CID) return false;
            partialHandle = handle;
        } else if (boundary != 1 || partialHandle != handle) return false;
        HciCodec.L2capPdu packet = fixed.accept(acl);
        if (packet != null) {
            partialHandle = -1;
            try { receiver.receive(packet); }
            finally { java.util.Arrays.fill(packet.payload, (byte)0); }
        }
        return true;
    }

    void reset() { fixed.reset(); partialHandle = -1; }
    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
}

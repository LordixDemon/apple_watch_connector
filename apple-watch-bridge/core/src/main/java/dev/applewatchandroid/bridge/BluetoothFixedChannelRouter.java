package dev.applewatchandroid.bridge;

/** Separates ATT/LE signaling from ACL without consuming IKE, SMP, BT_CL, or active ATT client responses. */
public final class BluetoothFixedChannelRouter {
    private final HciCodec.AclReassembler fixed = new HciCodec.AclReassembler();
    private int partialHandle = -1;
    private volatile boolean attClientActive;

    @FunctionalInterface public interface Receiver {
        void receive(HciCodec.L2capPdu packet) throws Exception;
    }

    @FunctionalInterface public interface FrameReceiver {
        void receive(int connectionHandle, int destinationCid, byte[] payload) throws Exception;
    }

    /**
     * Enables or disables pass-through for Server-to-Client ATT response PDUs so an active
     * local {@code GattAttClient} can receive Watch responses without disabling concurrent
     * local GATT server handling of Watch-initiated ATT requests.
     */
    public void setAttClientActive(boolean active) {
        this.attClientActive = active;
    }

    public boolean isAttClientActive() {
        return attClientActive;
    }

    public boolean acceptFrames(byte[] acl, int activeHandle, FrameReceiver receiver) throws Exception {
        return accept(acl, activeHandle, packet -> receiver.receive(
                packet.connectionHandle, packet.destinationCid, packet.payload));
    }

    public boolean accept(byte[] acl, int activeHandle, Receiver receiver) throws Exception {
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
            if (attClientActive
                    && cid == BluetoothLinkMaintenance.ATT_CID
                    && acl.length >= 9
                    && BluetoothLinkMaintenance.isAttClientResponseOpcode(acl[8] & 0xff)) {
                return false;
            }
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

    public void reset() { fixed.reset(); partialHandle = -1; }
    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
}

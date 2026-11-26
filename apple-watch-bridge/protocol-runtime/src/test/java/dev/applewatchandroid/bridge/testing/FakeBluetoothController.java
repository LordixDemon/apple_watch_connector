package dev.applewatchandroid.bridge.testing;

import dev.applewatchandroid.bridge.BluetoothController;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reusable in-memory {@link BluetoothController} test double for driving the real
 * {@code HalTransportSession} across Strategies 1–4 without reflection or host hardware.
 */
public final class FakeBluetoothController implements BluetoothController {
    public static final int DEFAULT_HANDLE = 0x0042;

    public interface PeerHandler {
        default void onScanEnabled(FakeBluetoothController controller) {}

        default void onLeConnected(
                FakeBluetoothController controller, int handle, int peerAddrType, byte[] peerAddr) {}

        default boolean onLeStartEncryption(
                FakeBluetoothController controller, int handle, byte[] rand, int ediv, byte[] ltk) {
            return true;
        }

        default void onAclFromCompanion(
                FakeBluetoothController controller, int handle, int cid, byte[] payload) {}

        default void onDisconnected(FakeBluetoothController controller, int handle) {}
    }

    private final List<PeerHandler> peers = new CopyOnWriteArrayList<>();
    private final BridgeCoreCodecs.AclReassemblerHandle txReassembler = new BridgeCoreCodecs.AclReassemblerHandle();
    private final AtomicInteger disconnectCount = new AtomicInteger();
    private final AtomicInteger scanDisableCount = new AtomicInteger();
    private final AtomicInteger startEncryptionCount = new AtomicInteger();

    private volatile Callbacks callbacks;
    private volatile boolean closed;
    private volatile int maxWatchAclLength = 251;
    private volatile byte[] localRandomAddress;
    private volatile int activeHandle = -1;
    private volatile byte[] lastStartEncRand;
    private volatile int lastStartEncEdiv = -1;
    private volatile byte[] lastStartEncLtk;

    public FakeBluetoothController addPeer(PeerHandler peer) {
        if (peer != null) {
            peers.add(peer);
        }
        return this;
    }

    public void setMaxWatchAclLength(int maxAclLength) {
        this.maxWatchAclLength = Math.max(9, maxAclLength);
    }

    public byte[] localRandomAddress() {
        return localRandomAddress == null ? null : localRandomAddress.clone();
    }

    public int disconnectCount() {
        return disconnectCount.get();
    }

    public int scanDisableCount() {
        return scanDisableCount.get();
    }

    public int startEncryptionCount() {
        return startEncryptionCount.get();
    }

    public byte[] lastStartEncRand() {
        return lastStartEncRand == null ? null : lastStartEncRand.clone();
    }

    public int lastStartEncEdiv() {
        return lastStartEncEdiv;
    }

    public byte[] lastStartEncLtk() {
        return lastStartEncLtk == null ? null : lastStartEncLtk.clone();
    }

    public void emitAdvertisingReport(int eventType, int addrType, byte[] addr, int rssi, byte[] adData) {
        Callbacks cb = callbacks;
        if (cb == null || closed) {
            return;
        }
        byte[] evt = new byte[14 + adData.length];
        evt[0] = 0x3E;
        evt[1] = (byte) (evt.length - 2);
        evt[2] = 0x02; // LE Advertising Report
        evt[3] = 0x01; // 1 report
        evt[4] = (byte) eventType;
        evt[5] = (byte) addrType;
        System.arraycopy(addr, 0, evt, 6, 6);
        evt[12] = (byte) adData.length;
        System.arraycopy(adData, 0, evt, 13, adData.length);
        evt[evt.length - 1] = (byte) rssi;
        cb.hciEventReceived(evt);
    }

    public void sendAclToCompanion(int handle, int cid, byte[] payload) {
        Callbacks cb = callbacks;
        if (cb == null || closed) {
            return;
        }
        for (byte[] frag : BridgeCoreCodecs.buildLeAclL2capFragments(handle, cid, payload, maxWatchAclLength)) {
            cb.aclDataReceived(frag);
        }
    }

    public void emitDisconnect(int handle, int reason) {
        Callbacks cb = callbacks;
        if (cb == null || closed) {
            return;
        }
        activeHandle = -1;
        cb.hciEventReceived(new byte[]{
                0x05, 0x04, 0x00,
                (byte) (handle & 0xFF), (byte) ((handle >> 8) & 0xFF),
                (byte) (reason & 0xFF)
        });
    }

    @Override
    public void initialize(Callbacks callbacks) {
        this.callbacks = callbacks;
        this.closed = false;
        callbacks.initializationComplete(SUCCESS);
    }

    @Override
    public void sendHciCommand(byte[] bytes) throws IOException {
        if (closed) {
            throw new IOException("FakeBluetoothController is closed");
        }
        if (bytes == null || bytes.length < 3) {
            throw new IOException("Truncated HCI command");
        }
        int opcode = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8);
        switch (opcode) {
            case 0x0C03 -> { // HCI_Reset
                txReassembler.reset();
                emitCommandComplete(opcode, new byte[]{0x00});
            }
            case 0x1009 -> // Read_BD_ADDR
                    emitCommandComplete(opcode, new byte[]{0x00, (byte) 0xA1, (byte) 0xA2, (byte) 0xA3, (byte) 0xA4, (byte) 0xA5, (byte) 0xA6});
            case 0x2005 -> { // LE_Set_Random_Address
                localRandomAddress = Arrays.copyOfRange(bytes, 3, 9);
                emitCommandComplete(opcode, new byte[]{0x00});
            }
            case 0x1005 -> // Read_Buffer_Size (aclLen=251, aclPkts=8)
                    emitCommandComplete(opcode, new byte[]{0x00, (byte) 0xFB, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00});
            case 0x2002 -> // LE_Read_Buffer_Size (leAclLen=251, leAclPkts=8)
                    emitCommandComplete(opcode, new byte[]{0x00, (byte) 0xFB, 0x00, 0x08});
            case 0x2003 -> // LE_Read_Local_Supported_Features (no DLE/2M so link setup is synchronous)
                    emitCommandComplete(opcode, new byte[]{0x00, 0, 0, 0, 0, 0, 0, 0, 0});
            case 0x200C -> { // LE_Set_Scan_Enable
                boolean enable = bytes.length > 3 && bytes[3] != 0;
                emitCommandComplete(opcode, new byte[]{0x00});
                if (enable) {
                    for (PeerHandler peer : peers) {
                        peer.onScanEnabled(this);
                    }
                } else {
                    scanDisableCount.incrementAndGet();
                }
            }
            case 0x200D -> { // LE_Create_Connection
                int peerAddrType = bytes[8] & 0xFF;
                byte[] peerAddr = Arrays.copyOfRange(bytes, 9, 15);
                activeHandle = DEFAULT_HANDLE;
                emitCommandStatus(opcode, 0x00);
                emitLeConnectionComplete(0x00, activeHandle, peerAddrType, peerAddr);
                for (PeerHandler peer : peers) {
                    peer.onLeConnected(this, activeHandle, peerAddrType, peerAddr.clone());
                }
            }
            case 0x2019 -> { // LE_Start_Encryption
                int handle = (bytes[3] & 0xFF) | ((bytes[4] & 0xFF) << 8);
                byte[] rand = Arrays.copyOfRange(bytes, 5, 13);
                int ediv = (bytes[13] & 0xFF) | ((bytes[14] & 0xFF) << 8);
                byte[] ltk = Arrays.copyOfRange(bytes, 15, 31);
                lastStartEncRand = rand.clone();
                lastStartEncEdiv = ediv;
                lastStartEncLtk = ltk.clone();
                startEncryptionCount.incrementAndGet();
                emitCommandStatus(opcode, 0x00);
                boolean ok = true;
                for (PeerHandler peer : peers) {
                    ok &= peer.onLeStartEncryption(this, handle, rand.clone(), ediv, ltk.clone());
                }
                emitEncryptionChange(ok ? 0x00 : 0x06, handle, ok ? 0x01 : 0x00);
            }
            case 0x0406 -> { // Disconnect
                int handle = (bytes[3] & 0xFF) | ((bytes[4] & 0xFF) << 8);
                disconnectCount.incrementAndGet();
                emitCommandStatus(opcode, 0x00);
                emitDisconnect(handle, 0x16);
                for (PeerHandler peer : peers) {
                    peer.onDisconnected(this, handle);
                }
            }
            default -> emitCommandComplete(opcode, new byte[]{0x00});
        }
    }

    @Override
    public void sendAclData(byte[] bytes) throws IOException {
        if (closed) {
            throw new IOException("FakeBluetoothController is closed");
        }
        int handle = ((bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8)) & 0x0FFF;
        emitCompletedPackets(handle, 1);
        BridgeCoreCodecs.L2capFrame frame = txReassembler.accept(bytes);
        if (frame == null) {
            return;
        }
        try {
            for (PeerHandler peer : peers) {
                peer.onAclFromCompanion(this, frame.connectionHandle(), frame.destinationCid(), frame.payload().clone());
            }
        } finally {
            Arrays.fill(frame.payload(), (byte) 0);
        }
    }

    @Override
    public void close() {
        closed = true;
        txReassembler.reset();
        if (lastStartEncRand != null) Arrays.fill(lastStartEncRand, (byte) 0);
        if (lastStartEncLtk != null) Arrays.fill(lastStartEncLtk, (byte) 0);
    }

    private void emitCommandComplete(int opcode, byte[] returnParams) {
        Callbacks cb = callbacks;
        if (cb == null || closed) return;
        byte[] evt = new byte[5 + returnParams.length];
        evt[0] = 0x0E;
        evt[1] = (byte) (3 + returnParams.length);
        evt[2] = 0x01;
        evt[3] = (byte) (opcode & 0xFF);
        evt[4] = (byte) ((opcode >> 8) & 0xFF);
        System.arraycopy(returnParams, 0, evt, 5, returnParams.length);
        cb.hciEventReceived(evt);
    }

    private void emitCommandStatus(int opcode, int status) {
        Callbacks cb = callbacks;
        if (cb == null || closed) return;
        cb.hciEventReceived(new byte[]{
                0x0F, 0x04, (byte) status, 0x01,
                (byte) (opcode & 0xFF), (byte) ((opcode >> 8) & 0xFF)
        });
    }

    private void emitLeConnectionComplete(int status, int handle, int peerAddrType, byte[] peerAddr) {
        Callbacks cb = callbacks;
        if (cb == null || closed) return;
        byte[] evt = new byte[21];
        evt[0] = 0x3E;
        evt[1] = 19;
        evt[2] = 0x01; // LE Connection Complete
        evt[3] = (byte) status;
        evt[4] = (byte) (handle & 0xFF);
        evt[5] = (byte) ((handle >> 8) & 0xFF);
        evt[6] = 0x00; // Master role
        evt[7] = (byte) peerAddrType;
        System.arraycopy(peerAddr, 0, evt, 8, 6);
        evt[14] = 0x18; evt[15] = 0x00; // interval
        evt[16] = 0x00; evt[17] = 0x00; // latency
        evt[18] = (byte) 0xC8; evt[19] = 0x00; // timeout
        evt[20] = 0x00;
        cb.hciEventReceived(evt);
    }

    private void emitEncryptionChange(int status, int handle, int enabled) {
        Callbacks cb = callbacks;
        if (cb == null || closed) return;
        cb.hciEventReceived(new byte[]{
                0x08, 0x04, (byte) status,
                (byte) (handle & 0xFF), (byte) ((handle >> 8) & 0xFF),
                (byte) enabled
        });
    }

    private void emitCompletedPackets(int handle, int count) {
        Callbacks cb = callbacks;
        if (cb == null || closed) return;
        cb.hciEventReceived(new byte[]{
                0x13, 0x05, 0x01,
                (byte) (handle & 0xFF), (byte) ((handle >> 8) & 0xFF),
                (byte) (count & 0xFF), (byte) ((count >> 8) & 0xFF)
        });
    }
}

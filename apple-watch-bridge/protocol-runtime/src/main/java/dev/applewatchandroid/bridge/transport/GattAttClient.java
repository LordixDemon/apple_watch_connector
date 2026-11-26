package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BridgeClock;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.HostException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Modular BLE GATT ATT client over {@link L2capChannelMux} for legacy Strategy 2/3 setup
 * ({@code NRExtensiblePair} service {@code 9AA4730F-...} and control characteristic {@code 5F6C6A23-...}).
 *
 * <p>Opens {@link HciTransport#openAttClientScope()} around each public client transaction so
 * Server-to-Client ATT responses pass through without interrupting concurrent Watch-initiated
 * ATT server requests.</p>
 */
public final class GattAttClient {
    public static final int DEFAULT_CLIENT_RX_MTU = 185;
    private static final int ATT_MIN_MTU = 23;
    private static final int ATT_OPCODE_NOTIFICATION = 0x1B;
    private static final int ATT_OPCODE_CONFIRMATION = 0x1E;
    private static final int ATT_OPCODE_MULTI_NOTIFICATION = 0x23;

    private final L2capChannelMux mux;

    public GattAttClient(L2capChannelMux mux) {
        this.mux = Objects.requireNonNull(mux, "mux");
    }

    public int exchangeMtu(int clientRxMtu, long timeoutMs) throws Exception {
        try (AutoCloseable scope = mux.transport().openAttClientScope()) {
            long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
            return exchangeMtuScoped(clientRxMtu, deadline);
        }
    }

    public BridgeCoreCodecs.AttHandleRange discoverExtensiblePairService(long timeoutMs) throws Exception {
        try (AutoCloseable scope = mux.transport().openAttClientScope()) {
            long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
            return discoverExtensiblePairServiceScoped(deadline);
        }
    }

    public int discoverExtensiblePairControlHandle(
            BridgeCoreCodecs.AttHandleRange range, long timeoutMs) throws Exception {
        Objects.requireNonNull(range, "range");
        try (AutoCloseable scope = mux.transport().openAttClientScope()) {
            long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
            return discoverExtensiblePairControlHandleScoped(range, deadline);
        }
    }

    public void writeCharacteristic(int valueHandle, byte[] payload, long timeoutMs) throws Exception {
        Objects.requireNonNull(payload, "payload");
        try (AutoCloseable scope = mux.transport().openAttClientScope()) {
            long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
            writeCharacteristicScoped(valueHandle, payload, deadline);
        }
    }

    /**
     * Executes the full {@code NRExtensiblePair} GATT setup sequence:
     * Exchange MTU -> discover {@code 9AA4730F} service -> discover {@code 5F6C6A23}
     * control characteristic -> write 6-byte {@code "NANO" + strategy + style}.
     *
     * @return the discovered control characteristic value handle that was written
     */
    public int writeNanoControl(int strategyWireId, int style, long timeoutMs) throws Exception {
        byte[] nanoPayload = BridgeCoreCodecs.encodeNanoControlPayload(strategyWireId, style);
        try (AutoCloseable scope = mux.transport().openAttClientScope()) {
            long deadline = BridgeClock.elapsedRealtime() + Math.max(0L, timeoutMs);
            int mtu = exchangeMtuScoped(DEFAULT_CLIENT_RX_MTU, deadline);
            mux.transport().log("GATT ATT MTU negotiated: " + mtu);
            BridgeCoreCodecs.AttHandleRange range = discoverExtensiblePairServiceScoped(deadline);
            int controlHandle = discoverExtensiblePairControlHandleScoped(range, deadline);
            writeCharacteristicScoped(controlHandle, nanoPayload, deadline);
            mux.transport().log(String.format(
                    Locale.US,
                    "GATT NANO control write acknowledged: strategy=%d style=%d handle=0x%04X",
                    strategyWireId,
                    style,
                    controlHandle));
            return controlHandle;
        } finally {
            Arrays.fill(nanoPayload, (byte) 0);
        }
    }

    private int exchangeMtuScoped(int clientRxMtu, long deadline) throws Exception {
        byte[] req = BridgeCoreCodecs.buildAttExchangeMtuRequest(clientRxMtu);
        mux.sendL2cap(BridgeCoreCodecs.ATT_FIXED_CID, req, "ATT EXCHANGE_MTU_REQ");
        byte[] rsp = awaitExpectedResponse(deadline, "ATT Exchange MTU");
        try {
            int opcode = rsp[0] & 0xFF;
            if (opcode == BridgeCoreCodecs.ATT_OPCODE_EXCHANGE_MTU_RSP) {
                int serverRxMtu = BridgeCoreCodecs.parseAttExchangeMtuResponse(rsp);
                return Math.max(ATT_MIN_MTU, Math.min(clientRxMtu, serverRxMtu));
            }
            if (opcode == BridgeCoreCodecs.ATT_OPCODE_ERROR_RSP) {
                BridgeCoreCodecs.AttErrorInfo err = BridgeCoreCodecs.parseAttErrorResponse(rsp);
                mux.transport().log("ATT MTU exchange error ignored: err=0x" + Integer.toHexString(err.errorCode()));
                return ATT_MIN_MTU;
            }
            throw new HostException("Unexpected ATT response opcode 0x" + Integer.toHexString(opcode)
                    + " to Exchange MTU");
        } finally {
            Arrays.fill(rsp, (byte) 0);
        }
    }

    private BridgeCoreCodecs.AttHandleRange discoverExtensiblePairServiceScoped(long deadline) throws Exception {
        byte[] findReq = BridgeCoreCodecs.buildAttFindExtensiblePairServiceRequest(1);
        mux.sendL2cap(BridgeCoreCodecs.ATT_FIXED_CID, findReq, "ATT FIND_BY_TYPE_VALUE 9AA4730F");
        byte[] findRsp = awaitExpectedResponse(deadline, "ATT Find NRExtensiblePair Service");
        try {
            int opcode = findRsp[0] & 0xFF;
            if (opcode == BridgeCoreCodecs.ATT_OPCODE_FIND_BY_TYPE_VALUE_RSP) {
                List<BridgeCoreCodecs.AttHandleRange> ranges =
                        BridgeCoreCodecs.parseAttFindByTypeValueResponse(findRsp);
                if (ranges.isEmpty()) {
                    throw new HostException("ATT FIND_BY_TYPE_VALUE returned empty handle range list");
                }
                return ranges.get(0);
            }
            if (opcode != BridgeCoreCodecs.ATT_OPCODE_ERROR_RSP) {
                throw new HostException("Unexpected ATT response opcode 0x"
                        + Integer.toHexString(opcode) + " to FIND_BY_TYPE_VALUE");
            }
            BridgeCoreCodecs.AttErrorInfo err = BridgeCoreCodecs.parseAttErrorResponse(findRsp);
            if (err.errorCode() != BridgeCoreCodecs.ATT_ERR_REQUEST_NOT_SUPPORTED
                    && err.errorCode() != BridgeCoreCodecs.ATT_ERR_UNSUPPORTED_GROUP_TYPE
                    && err.errorCode() != BridgeCoreCodecs.ATT_ERR_ATTRIBUTE_NOT_FOUND) {
                throw new HostException(String.format(
                        Locale.US,
                        "NRExtensiblePair GATT service 9AA4730F discovery failed (err=0x%02X)",
                        err.errorCode()));
            }
        } finally {
            Arrays.fill(findRsp, (byte) 0);
        }

        return discoverExtensiblePairServiceByGroupType(deadline);
    }

    private BridgeCoreCodecs.AttHandleRange discoverExtensiblePairServiceByGroupType(long deadline) throws Exception {
        int start = 1;
        while (start <= 0xFFFF) {
            byte[] req = BridgeCoreCodecs.buildAttReadPrimaryServicesRequest(start, 0xFFFF);
            mux.sendL2cap(BridgeCoreCodecs.ATT_FIXED_CID, req, "ATT READ_BY_GROUP_TYPE from " + start);
            byte[] rsp = awaitExpectedResponse(deadline, "ATT Read Primary Services");
            try {
                int opcode = rsp[0] & 0xFF;
                if (opcode == BridgeCoreCodecs.ATT_OPCODE_ERROR_RSP) {
                    BridgeCoreCodecs.AttErrorInfo err = BridgeCoreCodecs.parseAttErrorResponse(rsp);
                    if (err.errorCode() == BridgeCoreCodecs.ATT_ERR_ATTRIBUTE_NOT_FOUND) {
                        break;
                    }
                    throw new HostException(String.format(
                            Locale.US,
                            "ATT READ_BY_GROUP_TYPE failed at 0x%04X (err=0x%02X)",
                            start,
                            err.errorCode()));
                }
                if (opcode != BridgeCoreCodecs.ATT_OPCODE_READ_BY_GROUP_TYPE_RSP) {
                    throw new HostException("Unexpected ATT response opcode 0x"
                            + Integer.toHexString(opcode) + " to READ_BY_GROUP_TYPE");
                }
                List<BridgeCoreCodecs.AttPrimaryService> services =
                        BridgeCoreCodecs.parseAttReadPrimaryServicesResponse(rsp);
                int maxEnd = start;
                for (BridgeCoreCodecs.AttPrimaryService svc : services) {
                    if (svc.extensiblePairService()) {
                        return new BridgeCoreCodecs.AttHandleRange(svc.startHandle(), svc.endHandle());
                    }
                    if (svc.endHandle() > maxEnd) {
                        maxEnd = svc.endHandle();
                    }
                }
                if (services.isEmpty() || maxEnd >= 0xFFFF) {
                    break;
                }
                start = maxEnd + 1;
            } finally {
                Arrays.fill(rsp, (byte) 0);
            }
        }
        throw new HostException("Watch does not expose NRExtensiblePair GATT service 9AA4730F");
    }

    private int discoverExtensiblePairControlHandleScoped(
            BridgeCoreCodecs.AttHandleRange range, long deadline) throws Exception {
        int start = range.startHandle();
        while (start <= range.endHandle()) {
            byte[] req = BridgeCoreCodecs.buildAttDiscoverCharacteristicsRequest(start, range.endHandle());
            mux.sendL2cap(BridgeCoreCodecs.ATT_FIXED_CID, req, "ATT READ_BY_TYPE 0x2803 from " + start);
            byte[] rsp = awaitExpectedResponse(deadline, "ATT Discover Control Characteristic");
            try {
                int opcode = rsp[0] & 0xFF;
                if (opcode == BridgeCoreCodecs.ATT_OPCODE_ERROR_RSP) {
                    BridgeCoreCodecs.AttErrorInfo err = BridgeCoreCodecs.parseAttErrorResponse(rsp);
                    if (err.errorCode() == BridgeCoreCodecs.ATT_ERR_ATTRIBUTE_NOT_FOUND) {
                        break;
                    }
                    throw new HostException(String.format(
                            Locale.US,
                            "ATT READ_BY_TYPE failed at 0x%04X (err=0x%02X)",
                            start,
                            err.errorCode()));
                }
                if (opcode != BridgeCoreCodecs.ATT_OPCODE_READ_BY_TYPE_RSP) {
                    throw new HostException("Unexpected ATT response opcode 0x"
                            + Integer.toHexString(opcode) + " to READ_BY_TYPE");
                }
                List<BridgeCoreCodecs.AttCharacteristic> chars =
                        BridgeCoreCodecs.parseAttDiscoverCharacteristicsResponse(rsp);
                int maxDecl = start;
                for (BridgeCoreCodecs.AttCharacteristic ch : chars) {
                    if (ch.extensiblePairControl()) {
                        return ch.valueHandle();
                    }
                    if (ch.declarationHandle() > maxDecl) {
                        maxDecl = ch.declarationHandle();
                    }
                }
                if (chars.isEmpty() || maxDecl >= range.endHandle()) {
                    break;
                }
                start = maxDecl + 1;
            } finally {
                Arrays.fill(rsp, (byte) 0);
            }
        }
        throw new HostException("NRExtensiblePair control characteristic 5F6C6A23 not found");
    }

    private void writeCharacteristicScoped(int valueHandle, byte[] payload, long deadline) throws Exception {
        byte[] req = BridgeCoreCodecs.buildAttWriteRequest(valueHandle, payload);
        try {
            mux.sendL2cap(BridgeCoreCodecs.ATT_FIXED_CID, req, "ATT WRITE_REQ handle=0x"
                    + Integer.toHexString(valueHandle));
        } finally {
            Arrays.fill(req, (byte) 0);
        }
        byte[] rsp = awaitExpectedResponse(deadline, "ATT Write Characteristic");
        try {
            int opcode = rsp[0] & 0xFF;
            if (opcode == BridgeCoreCodecs.ATT_OPCODE_ERROR_RSP) {
                BridgeCoreCodecs.AttErrorInfo err = BridgeCoreCodecs.parseAttErrorResponse(rsp);
                throw new HostException(String.format(
                        Locale.US,
                        "Watch rejected ATT characteristic write: handle=0x%04X err=0x%02X",
                        err.attributeHandle(),
                        err.errorCode()));
            }
            if (!BridgeCoreCodecs.isAttWriteResponse(rsp)) {
                throw new HostException("Unexpected ATT response opcode 0x"
                        + Integer.toHexString(opcode) + " to WRITE_REQ");
            }
        } finally {
            Arrays.fill(rsp, (byte) 0);
        }
    }

    private byte[] awaitExpectedResponse(long deadline, String label) throws Exception {
        while (true) {
            long remaining = deadline - BridgeClock.elapsedRealtime();
            if (remaining <= 0) {
                throw new HostException("Timed out waiting for " + label);
            }
            byte[] rsp = mux.awaitAttClientResponse(remaining, label);
            if (rsp.length == 0) {
                continue;
            }
            int opcode = rsp[0] & 0xFF;
            if (opcode == ATT_OPCODE_NOTIFICATION
                    || opcode == ATT_OPCODE_CONFIRMATION
                    || opcode == ATT_OPCODE_MULTI_NOTIFICATION) {
                Arrays.fill(rsp, (byte) 0);
                continue;
            }
            return rsp;
        }
    }
}

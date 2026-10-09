package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local IDS account RPCs. Bridge currently has no registered Apple accounts. */
final class IdsRemoteAccountExchange {
    static final long SYNC_REQUEST = 15;
    static final long SYNC_RESPONSE = 16;
    static final long FETCH_REQUEST = 17;
    static final long FETCH_RESPONSE = 18;

    private IdsRemoteAccountExchange() {}

    @FunctionalInterface interface SpsReceiver {
        void persist(IdsSpsCompanionInfo info) throws Exception;
    }

    static Reply accept(byte[] payload) {
        try { return accept(payload, null); }
        catch (RuntimeException invalid) { throw invalid; }
        catch (Exception impossible) { throw new IllegalStateException("Unexpected SPS receiver error", impossible); }
    }

    static Reply accept(byte[] payload, SpsReceiver receiver) throws Exception {
        if (payload == null || payload.length > 32 * 1024) {
            throw new IllegalArgumentException("Invalid IDS account request size");
        }
        Object decoded = AppleBinaryPropertyList.decode(payload);
        try {
            if (!(decoded instanceof Map<?, ?> request)
                    || !(request.get("command") instanceof Long command)) {
                throw new IllegalArgumentException("Invalid IDS account request dictionary");
            }
            if (command != FETCH_REQUEST && command != SYNC_REQUEST) return null;
            if (!(request.get("unique-id") instanceof String correlation)
                    || correlation.length() != 36
                    || !UUID.fromString(correlation).toString().equalsIgnoreCase(correlation)) {
                throw new IllegalArgumentException("Invalid IDS account request correlation");
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("unique-id", correlation);
            if (command == FETCH_REQUEST) {
                if (!(request.get("serviceTypes") instanceof List<?> services) || services.size() > 64) {
                    throw new IllegalArgumentException("Invalid IDS account service list");
                }
                Map<String, Object> accounts = new LinkedHashMap<>();
                for (Object service : services) {
                    if (!(service instanceof String name) || name.length() > 255
                            || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                        throw new IllegalArgumentException("Invalid IDS account service topic");
                    }
                    // Native constructRAResponseDictionary: maps each requested
                    // push topic to _constructAccountInfo:'s account array.
                    accounts.put(name, List.of());
                }
                response.put("command", FETCH_RESPONSE);
                response.put("accountMap", accounts);
                // Native FetchRA response has no synthetic success/login flag.
                return new Reply(FETCH_RESPONSE, AppleBinaryPropertyList.encode(response),
                        "remote accounts: requestedServices=" + services.size() + " localAccounts=0");
            }
            if (!(request.get("sync-payload") instanceof Map<?, ?> sync)) {
                throw new IllegalArgumentException("Invalid IDS account sync payload");
            }
            response.put("command", SYNC_RESPONSE);
            if (Long.valueOf(5).equals(sync.get("command")) && receiver != null) {
                try (IdsSpsCompanionInfo info = IdsSpsCompanionInfo.fromMessage(sync)) {
                    // IDSCredentialsAgent calls IDSIncomingAccountSyncMessage
                    // before replying 16. Here receipt is durable before ACK.
                    receiver.persist(info);
                    response.put("success", true);
                    return new Reply(SYNC_RESPONSE, AppleBinaryPropertyList.encode(response),
                            info.summary() + "; persisted=true; receipt acknowledged");
                }
            }
            // Other account operations still have no implementation or success.
            response.put("success", false);
            return new Reply(SYNC_RESPONSE, AppleBinaryPropertyList.encode(response),
                    "account sync unsupported; success=false; no accounts changed");
        } finally {
            IdsMessageProtectionIdentity.wipeValues(decoded);
        }
    }

    static final class Reply implements AutoCloseable {
        final long command;
        final byte[] payload;
        final String summary;

        Reply(long command, byte[] payload, String summary) {
            this.command = command;
            this.payload = payload;
            this.summary = summary;
        }

        @Override public void close() { Arrays.fill(payload, (byte) 0); }
    }
}

package dev.applewatchandroid.bridge;

/**
 * Runtime route state for the NanoPreferencesSync PairedSync service.
 *
 * <p>iOS asks the active paired device whether it supports capability
 * {@code 36A0EB23-E045-4E99-9D71-8FB9A853ADA7}. A positive answer selects
 * {@code preferencessync.pairedsync}; a negative answer selects the ordinary
 * {@code preferencessync} service. Until the capability arrives, use ordinary
 * preferences: native nanoprefsyncd registers its type-0 defaults handler on
 * both services. UNKNOWN remains an unobserved capability, not an absent one.</p>
 *
 * <p>The watchOS 26 property packer sends this property as a protobuf set of
 * full UUID values. {@code NRMiniUUIDSet} is an iPhone-side representation and
 * is deliberately not accepted as an alternative wire shape here.</p>
 */
final class PairedSyncCapabilityState {
    enum Status {
        UNKNOWN,
        PRESENT,
        ABSENT
    }

    static final String PROPERTY_NAME =
            "capabilities";
    static final int CAPABILITY_ID =
            0x36a0eb23;

    private static final byte[] CAPABILITY_UUID =
            new byte[] {
                    0x36, (byte) 0xa0, (byte) 0xeb, 0x23,
                    (byte) 0xe0, 0x45, 0x4e, (byte) 0x99,
                    (byte) 0x9d, 0x71, (byte) 0x8f, (byte) 0xb9,
                    (byte) 0xa8, 0x53, (byte) 0xad, (byte) 0xa7
            };
    private static final String CAPABILITY_UUID_STRING =
            "36a0eb23-e045-4e99-9d71-8fb9a853ada7";

    private Status status =
            Status.UNKNOWN;
    private String inboundObservedService;

    Status status() {
        return status;
    }

    void setStatus(Status status) {
        if (status == null) {
            throw new IllegalArgumentException("Status cannot be null");
        }
        this.status = status;
    }

    Status apply(
            NanoRegistryPropertyCodec.PropertiesChanged changed) {
        if (changed == null) {
            throw new IllegalArgumentException(
                    "NanoRegistry capability change is absent");
        }

        NanoRegistryPropertyCodec.Property capabilities =
                null;
        for (NanoRegistryPropertyCodec.Property property :
                changed.properties) {
            if (!PROPERTY_NAME.equals(
                    property.name)) {
                continue;
            }
            if (capabilities != null) {
                throw new IllegalArgumentException(
                        "Duplicate NanoRegistry Capabilities property");
            }
            capabilities = property;
        }

        if (capabilities == null) {
            if (changed.thisIsAllOfThem) {
                throw new IllegalArgumentException(
                        "Full NanoRegistry snapshot omits Capabilities");
            }
            return status;
        }

        Status next =
                capabilities.value == null
                        ? Status.ABSENT
                        : NanoRegistryUuidSetCodec
                        .decode(
                                capabilities.value)
                        .contains(
                                CAPABILITY_UUID_STRING)
                        ? Status.PRESENT
                        : Status.ABSENT;
        status = next;
        return status;
    }

    /**
     * The Watch's own PairedSync traffic proves the route it uses: a direct
     * reconnect of a committed session never delivers the Capabilities
     * property, but the Watch keeps publishing user-defaults on its chosen
     * service. Remembering that service substitutes for the capability
     * answer when the status is still UNKNOWN (live 0.2.186).
     */
    void observeInboundService(
            String service) {
        if (PairedSyncCodec.PREFERRED_SERVICE.equals(
                service)
                || PairedSyncCodec.FALLBACK_SERVICE.equals(
                        service)) {
            inboundObservedService =
                    service;
        }
    }

    String requireService() {
        if (status == Status.PRESENT) {
            return PairedSyncCodec.PREFERRED_SERVICE;
        }
        if (status == Status.ABSENT) {
            return PairedSyncCodec.FALLBACK_SERVICE;
        }
        if (inboundObservedService != null) {
            return inboundObservedService;
        }
        return PairedSyncCodec.FALLBACK_SERVICE;
    }

    static byte[] capabilityUuid() {
        return CAPABILITY_UUID.clone();
    }

}

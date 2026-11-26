package dev.applewatchandroid.bridge.spi;

/** Post-SMP transport bearer carrying IDS control and application services. */
public enum IdsLinkKind {
    /** Strategy 4: BT_CL terminusLink (0x0002) -> NetworkRelay IPsec ESP -> IPv6/TCP/NWSC. */
    NETWORK_RELAY_ESP(1),
    /** Strategy 3: BT_CL terminusLink (0x0002) -> Direct IPSec OOBK/IDS-key IKEv2. */
    DIRECT_IPSEC_OOBK(2),
    /** Strategy 1/2 (and S3 fallback): BT_CL com.apple.ids (0x0003) -> IDSBTLink ERTM stream. */
    IDS_BT_LINK(3);

    private final int wireValue;

    IdsLinkKind(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public int wireId() {
        return wireValue;
    }

    public static IdsLinkKind fromWireValue(int value) {
        for (IdsLinkKind kind : values()) {
            if (kind.wireValue == value) {
                return kind;
            }
        }
        return null;
    }

    public static IdsLinkKind fromWireId(int value) {
        return fromWireValue(value);
    }
}

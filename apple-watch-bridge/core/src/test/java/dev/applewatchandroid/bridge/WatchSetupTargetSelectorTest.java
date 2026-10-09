package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import org.junit.Test;

public final class WatchSetupTargetSelectorTest {
    private static HciCodec.AdvertisingReport report(int type, int peer, boolean setup) {
        byte[] data = setup ? new byte[]{0x10,0x16,0x25,(byte)0xfe,0x06,
                0x20,(byte)0x86,(byte)0x93,(byte)0xf4,(byte)0xce,0x64,0x38,0x50,0,(byte)0xd0,0x10,0}
                : new byte[]{4,(byte)0xff,0x4c,0,0x10};
        return new HciCodec.AdvertisingReport(type,1,new byte[]{(byte)peer,2,3,4,5,6},-40,data,
                HciCodec.parseWatchSetupPayload(data));
    }

    @Test
    public void genericAppleBeaconsCannotBecomeSyntheticPinSetupData() {
        var selector = new WatchSetupTargetSelector();
        assertNull(selector.accept(report(0,1,false), 0));
        var valid = report(0,1,true);
        var shortData = new HciCodec.WatchSetupPayload(new byte[]{0x20,0,0,0,0},1,
                valid.setup.candidateIdentifier, valid.setup.metadata,
                valid.setup.decodedIdentifier, valid.setup.decodedMetadata);
        assertNull(selector.accept(new HciCodec.AdvertisingReport(0,1,valid.address,-20,
                valid.data,shortData), 100));
        assertSame(valid, selector.accept(valid, 200));
    }

    @Test
    public void scanResponseNeedsRecentConnectabilityFromTheSameAddress() {
        var selector = new WatchSetupTargetSelector();
        assertNull(selector.accept(report(4,1,true), 0));
        assertNull(selector.accept(report(0,1,false), 10));
        assertNull(selector.accept(report(4,2,true), 20));
        var scanResponse = report(4,1,true);
        var selected = selector.accept(scanResponse, 30);
        assertNotNull(selected);
        assertTrue(selected.isExpectedTarget());
        assertArrayEquals(scanResponse.setup.watchSetupData, selected.setup.watchSetupData);
        assertNull(selector.accept(scanResponse, 3000));
        assertNull(selector.accept(report(2,1,false), 3010));
        assertNull(selector.accept(scanResponse, 3020));
    }

    @Test
    public void activeScanRequestsResponsesUsingTheConfiguredIdentityType() {
        assertArrayEquals(new byte[]{1,0x60,0,0x60,0,1,0}, HciCodec.activeScanParameters(1));
        assertThrows(IllegalArgumentException.class, () -> HciCodec.activeScanParameters(3));
    }
}

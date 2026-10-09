package dev.applewatchandroid.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TelephonyRelayCodecTest {

    @Test
    public void testIncomingCallAlertEncodeDecode() {
        TelephonyRelayCodec.IncomingCallAlert alert =
                new TelephonyRelayCodec.IncomingCallAlert("call-99", "John Doe", "+1234567890", false);

        byte[] encoded = alert.encode();
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        TelephonyRelayCodec.IncomingCallAlert decoded =
                TelephonyRelayCodec.IncomingCallAlert.decode(encoded);

        assertEquals("call-99", decoded.callId);
        assertEquals("John Doe", decoded.callerName);
        assertEquals("+1234567890", decoded.callerNumber);
        assertEquals(false, decoded.isVideo);
    }

    @Test
    public void testCallActionEncodeDecode() {
        TelephonyRelayCodec.CallAction action =
                new TelephonyRelayCodec.CallAction("call-99", TelephonyRelayCodec.ACTION_ANSWER);

        byte[] encoded = action.encode();
        TelephonyRelayCodec.CallAction decoded =
                TelephonyRelayCodec.CallAction.decode(encoded);

        assertEquals("call-99", decoded.callId);
        assertEquals(TelephonyRelayCodec.ACTION_ANSWER, decoded.action);
    }
}

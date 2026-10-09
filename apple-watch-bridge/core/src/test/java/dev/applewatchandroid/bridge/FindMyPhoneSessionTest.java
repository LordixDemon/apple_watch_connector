package dev.applewatchandroid.bridge;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FindMyPhoneSessionTest {
    private final UUID epoch = UUID.randomUUID();
    private final String id = UUID.randomUUID().toString();
    private final long wall = 1791250000000L;
    private byte[] payload(double seconds) { return new FindMyLocalDeviceCodec.PlaySoundRequest(seconds, null).encode(); }
    @Test public void onlyFreshNativeRequestsCanStartAnEffect() {
        var session = new FindMyPhoneSession();
        assertNull(session.receive(1, false, id, payload(wall / 1000.0), wall, 100));
        session.reset(epoch);
        assertNull(session.receive(1, true, id, payload(wall / 1000.0), wall, 100));
        assertNull(session.receive(3, false, id, payload(wall / 1000.0), wall, 100));
        assertNull(session.receive(1, false, id, payload(wall / 1000.0 - 30), wall, 100));
        assertNull(session.receive(1, false, id, payload(wall / 1000.0 + 6), wall, 100));
        var incoming = session.receive(1, false, id, payload(wall / 1000.0), wall, 100);
        assertNotNull(incoming.request());
        assertEquals(10100, incoming.request().deadline());
        assertNull(session.receive(1, false, id, payload(wall / 1000.0), wall, 101).request());
        assertThrows(IllegalArgumentException.class,
                () -> session.receive(1, false, id, payload(wall / 1000.0 + 1), wall, 102));
        assertThrows(IllegalArgumentException.class,
                () -> session.receive(2, false, id, payload(wall / 1000.0), wall, 102));
    }
    @Test public void resultRequiresCurrentEpochTypeDeadlineAndIsConsumedOnce() {
        var session = new FindMyPhoneSession(); session.reset(epoch);
        var request = session.receive(1, false, id, payload(wall / 1000.0), wall, 100).request();
        assertNull(session.complete(new FindMyPhoneIpcCodec.Result(UUID.randomUUID(), id, 1, true), 101));
        assertNull(session.complete(new FindMyPhoneIpcCodec.Result(epoch, id, 2, true), 101));
        assertNull(session.complete(new FindMyPhoneIpcCodec.Result(epoch, UUID.randomUUID().toString(), 1, true), 101));
        var result = new FindMyPhoneIpcCodec.Result(epoch, id, 1, false);
        assertEquals(request, session.complete(result, 101));
        assertNull(session.complete(result, 102));
        var duplicate = session.receive(1, false, id.toUpperCase(Locale.ROOT), payload(wall / 1000.0), wall, 103);
        assertNull(duplicate.request()); assertEquals(result, duplicate.cached());
        session.reset(epoch);
        session.receive(1, false, id, payload(wall / 1000.0), wall, 100);
        assertNull(session.complete(result, 10100));
        session.reset(UUID.randomUUID()); assertFalse(session.current(epoch));
        assertNull(session.complete(result, 10101));
    }
    @Test public void boundsLedgerAndExpiresWithoutExtendingDuplicateDeadline() {
        var session = new FindMyPhoneSession(); session.reset(epoch);
        session.receive(1, false, id, payload(wall / 1000.0), wall, 100);
        session.receive(1, false, id, payload(wall / 1000.0), wall, 9999);
        assertNull(session.complete(new FindMyPhoneIpcCodec.Result(epoch, id, 1, true), 10100));
        for (int i = 1; i < 64; i++) session.receive(1, false, UUID.randomUUID().toString(), payload(wall / 1000.0), wall, 100);
        assertNull(session.receive(1, false, UUID.randomUUID().toString(), payload(wall / 1000.0), wall, 101));
        assertNotNull(session.receive(1, false, UUID.randomUUID().toString(), payload(wall / 1000.0), wall, 60100).request());
    }
}

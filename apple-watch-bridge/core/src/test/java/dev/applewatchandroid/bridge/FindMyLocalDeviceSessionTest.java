package dev.applewatchandroid.bridge;

import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public class FindMyLocalDeviceSessionTest {
    @Test public void repliesRequireExactUuidTypeDirectionAndAreConsumedOnce() {
        var session = new FindMyLocalDeviceSession();
        String id = UUID.randomUUID().toString();
        session.queued(1, false, id, 100);
        assertNull(session.receive(1, true, UUID.randomUUID().toString(), new byte[]{8, 1}, 101));
        assertNull(session.receive(2, true, id, new byte[]{8, 1}, 101));
        assertNull(session.receive(1, false, id, new byte[]{8, 1}, 101));
        assertThrows(IllegalArgumentException.class,
                () -> session.receive(1, true, id, new byte[0], 101));
        assertFalse(session.receive(1, true, id, new byte[]{8, 0}, 101).didPlay());
        assertNull(session.receive(1, true, id, new byte[]{8, 1}, 102));
    }

    @Test public void reconnectAndDeadlineCannotResurrectAReply() {
        var session = new FindMyLocalDeviceSession();
        String id = UUID.randomUUID().toString();
        session.queued(1, false, id, 100);
        session.reset();
        assertNull(session.receive(1, true, id, new byte[]{8, 1}, 101));
        session.queued(1, false, id, 100);
        assertNull(session.receive(1, true, id, new byte[]{8, 1}, 60100));
        session.queued(1, true, id, 60101);
        assertNull(session.receive(1, true, id, new byte[]{8, 1}, 60102));
    }

    @Test public void correlationWindowIsBoundedAndDoesNotExtendOnDuplicateSend() {
        var session = new FindMyLocalDeviceSession();
        String id = UUID.randomUUID().toString();
        session.queued(1, false, id, 100);
        session.queued(1, false, id, 59000);
        assertNull(session.receive(1, true, id, new byte[]{8, 1}, 60100));
        for (int i = 0; i < 8; i++) session.queued(1, false, UUID.randomUUID().toString(), 61000);
        assertThrows(IllegalStateException.class,
                () -> session.queued(1, false, UUID.randomUUID().toString(), 61001));
        session.queued(1, false, UUID.randomUUID().toString(), 121000);
    }
}

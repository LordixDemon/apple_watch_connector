package dev.applewatchandroid.bridge;

import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FindMyPhoneClaimsTest {
    private static class Memory implements FindMyPhoneClaims.Backend {
        byte[] bytes = new byte[0]; boolean fail; int writes;
        public byte[] read() { return bytes.clone(); }
        public void write(byte[] frame) throws IOException {
            if (fail) throw new IOException("disk unavailable");
            bytes = frame.clone(); writes++;
        }
    }
    private final String pair = UUID.randomUUID().toString();
    private FindMyPhoneIpcCodec.Request request(double timestamp, int behavior) {
        return new FindMyPhoneIpcCodec.Request(UUID.randomUUID(), UUID.randomUUID().toString(), 1,
                new FindMyLocalDeviceCodec.PlaySoundRequest(timestamp, behavior), 10001);
    }
    @Test public void durablePendingSurvivesCrashAndNewIdsIdentifiersWithoutReplay() throws Exception {
        var disk = new Memory(); var claims = new FindMyPhoneClaims(disk);
        var first = claims.claim(pair, request(1791250000, 0), 1791250000000L);
        assertTrue(first.fresh()); assertEquals(1, disk.writes);
        var restarted = new FindMyPhoneClaims(disk);
        var duplicate = restarted.claim(pair, request(1791250000, 0), 1791250000010L);
        assertFalse(duplicate.fresh()); assertNull(duplicate.previousResult());
        assertEquals(1, disk.writes);
        assertTrue(restarted.claim(UUID.randomUUID().toString(), request(1791250000, 0), 1791250000010L).fresh());
        assertTrue(restarted.claim(pair, request(1791250000, 1), 1791250000010L).fresh());
    }
    @Test public void completedResultPersistsWithoutRepeatingEffect() throws Exception {
        for (boolean played : new boolean[]{false, true}) {
            var disk = new Memory(); var claims = new FindMyPhoneClaims(disk);
            var claim = claims.claim(pair, request(1791250000, 0), 1791250000000L);
            claims.complete(claim, played);
            assertThrows(IOException.class, () -> claims.complete(claim, !played));
            var duplicate = new FindMyPhoneClaims(disk).claim(pair, request(1791250000, 0), 1791250000010L);
            assertFalse(duplicate.fresh()); assertEquals(Boolean.valueOf(played), duplicate.previousResult());
            assertEquals(2, disk.writes);
        }
    }
    @Test public void failedCommitBeforeEffectAndAfterEffectRemainConservative() throws Exception {
        var disk = new Memory(); var claims = new FindMyPhoneClaims(disk); disk.fail = true;
        assertThrows(IOException.class, () -> claims.claim(pair, request(1791250000, 0), 1791250000000L));
        assertEquals(0, disk.bytes.length);
        disk.fail = false;
        var claim = claims.claim(pair, request(1791250000, 0), 1791250000000L);
        disk.fail = true;
        assertThrows(IOException.class, () -> claims.complete(claim, true));
        assertNull(new FindMyPhoneClaims(disk).claim(pair, request(1791250000, 0), 1791250000010L).previousResult());
    }
    @Test public void boundedClaimWindowExpiresAndCorruptDataNeverBecomesFresh() throws Exception {
        var disk = new Memory(); var claims = new FindMyPhoneClaims(disk);
        for (int i = 0; i < 64; i++) assertTrue(claims.claim(pair, request(1791250000 + i, 0), 1791250000000L).fresh());
        assertEquals(FindMyPhoneClaims.MAX_FRAME, disk.bytes.length);
        assertThrows(IOException.class, () -> claims.claim(pair, request(1791250064, 0), 1791250000001L));
        assertTrue(claims.claim(pair, request(1791250064, 0), 1791250060000L).fresh());
        byte[] valid = disk.bytes.clone();
        for (int n = 1; n < valid.length; n++) {
            disk.bytes = Arrays.copyOf(valid, n);
            assertThrows(IOException.class, () -> new FindMyPhoneClaims(disk));
        }
        disk.bytes = valid.clone(); disk.bytes[disk.bytes.length - 1] = 3;
        assertThrows(IOException.class, () -> new FindMyPhoneClaims(disk));
        disk.bytes = new byte[FindMyPhoneClaims.MAX_FRAME + 1];
        assertThrows(IOException.class, () -> new FindMyPhoneClaims(disk));
    }
}

package dev.applewatchandroid.bridge;

import java.util.HashMap;
import java.util.Map;

/** Controller packet buffers, independent of peer L2CAP acknowledgements. */
final class HciAclCreditWindow {
    private final Map<Integer, Integer> pending = new HashMap<>();
    private int capacity;
    private int used;
    private long sent;
    private long completed;
    private long reclaimed;

    synchronized void configure(int packetCount) {
        if (packetCount < 1 || packetCount > 65535 || used != 0) {
            throw new IllegalArgumentException("Invalid controller ACL buffer count");
        }
        capacity = packetCount;
    }

    synchronized void connected(int handle) {
        if (handle < 0 || handle > 0x0EFF || pending.containsKey(handle)) {
            throw new IllegalStateException("Invalid or duplicate HCI connection handle");
        }
        pending.put(handle, 0);
        notifyAll();
    }

    synchronized boolean tryReserve(int handle) {
        Integer outstanding = pending.get(handle);
        if (capacity == 0 || outstanding == null) {
            throw new IllegalStateException("ACL send without configured live connection");
        }
        if (used == capacity) return false;
        pending.put(handle, outstanding + 1);
        used++;
        sent++;
        return true;
    }

    synchronized void complete(int handle, int count) {
        Integer outstanding = pending.get(handle);
        // The controller may still report a flushed handle after disconnection.
        if (outstanding == null) return;
        if (count < 0 || count > outstanding) {
            throw new IllegalStateException("Controller completed more ACL packets than sent");
        }
        pending.put(handle, outstanding - count);
        used -= count;
        completed += count;
        notifyAll();
    }

    synchronized void disconnected(int handle) {
        Integer outstanding = pending.remove(handle);
        if (outstanding != null) {
            used -= outstanding;
            reclaimed += outstanding;
        }
        notifyAll();
    }

    synchronized void awaitCredit(long millis) throws InterruptedException {
        // Recheck under the monitor: completion may precede entry into wait().
        if (used == capacity) wait(millis);
    }

    synchronized String summary() {
        return "used=" + used + "/" + capacity + " sent=" + sent
                + " completed=" + completed + " reclaimed=" + reclaimed;
    }
}

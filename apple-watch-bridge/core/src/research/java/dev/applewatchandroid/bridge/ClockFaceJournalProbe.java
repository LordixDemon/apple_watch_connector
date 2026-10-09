package dev.applewatchandroid.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Local app_process diagnostic: journals one explicitly supplied historical capture.
 * Never opens Bluetooth, sends IDS, changes setup, or claims a live Watch operation.
 */
public final class ClockFaceJournalProbe {
    private ClockFaceJournalProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[2].matches("[0-9]+-clockface-sync[.]bin")) {
            throw new IllegalArgumentException("Expected canonical pair, private journal root, capture basename");
        }
        Path root = java.nio.file.Paths.get(args[1]);
        if (!root.toString().equals("/data/user/0/dev.applewatchandroid.bridge/files/clockface-probe-352")) {
            throw new IllegalArgumentException("Probe journal must use its separate private directory");
        }
        Path capture = java.nio.file.Paths.get("/data/user/0/dev.applewatchandroid.bridge/files/activation-capture", args[2]);
        if (Files.size(capture) > ClockFaceSyncFrame.MAX_BYTES) throw new IllegalArgumentException("Capture too large");
        byte[] request = Files.readAllBytes(capture);
        try {
            ClockFaceSyncJournal.Receipt receipt = new ClockFaceSyncJournal(root, args[0]).commit(request);
            System.out.println("historicalCapture=true liveDelivery=false application=unverified"
                    + " duplicate=" + receipt.duplicate() + " changes=" + receipt.changes()
                    + " bytes=" + receipt.bytes());
        } finally { Arrays.fill(request, (byte) 0); }
    }
}

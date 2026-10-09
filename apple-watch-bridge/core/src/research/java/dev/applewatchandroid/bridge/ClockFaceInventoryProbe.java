package dev.applewatchandroid.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/** Read-only production snapshot diagnostic; no historical import or transport access. */
public final class ClockFaceInventoryProbe {
    private ClockFaceInventoryProbe() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected canonical pair");
        Path root = Paths.get("/data/user/0/dev.applewatchandroid.bridge/files");
        ClockFaceCollection collection = new ClockFaceSyncReceiver(root.resolve("clockface-journal"),
                root.resolve("clockface-receiver"), args[0]).snapshot();
        System.out.println("productionSnapshot=true observedFaces=" + collection.configurations.size()
                + " complete=" + collection.complete() + " observedAt=" + collection.observedAt
                + " orderKnown=" + collection.orderKnown + " selectionKnown=" + collection.selectionKnown);
        if (collection.selectionKnown) System.out.println("selectedUUID=" + collection.selected);
        if (collection.orderKnown) System.out.println("orderedUUIDs=" + collection.ordered);
        for (var entry : collection.configurations.entrySet()) System.out.println("faceUUID=" + entry.getKey()
                + " bundle=" + NtkFacePayloadCodec.configurationBundle(entry.getValue()) + " configBytes=" + entry.getValue().length);
        Path client = root.resolve("clockface-client").resolve(args[0]).resolve("client-state.bplist");
        if (Files.isRegularFile(client) && Files.size(client) <= 16384) {
            Object decoded = AppleBinaryPropertyList.decode(Files.readAllBytes(client));
            if (decoded instanceof Map<?, ?> map) System.out.println("reservedSequence=" + map.get("sequence")
                    + " lastFullRequest=" + map.get("lastRequest"));
        }
    }
}

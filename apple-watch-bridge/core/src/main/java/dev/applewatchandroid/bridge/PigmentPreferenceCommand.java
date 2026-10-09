package dev.applewatchandroid.bridge;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Internal manual-delta command. Native NPS bytes are derived on the HAL send thread. */
final class PigmentPreferenceCommand {
    static final String PREFIX = "NATIVE_PIGMENTS_UPDATE:";
    private static final int MAGIC = 0x50475031, MAX_FRAME = 64 * 1024;
    private static final long MAX_BASELINE_AGE = 300_000;
    record Command(UUID pair, double sourceTimestamp, String baselineHash, Map<String, Boolean> changes,
                   Double automaticTimestamp, String automaticHash) {
        Command {
            if (pair == null || !Double.isFinite(sourceTimestamp) || sourceTimestamp < 0
                    || baselineHash == null || !baselineHash.matches("[0-9a-f]{64}")
                    || changes == null || changes.isEmpty()) {
                throw new IllegalArgumentException("Invalid pigment command");
            }
            if ((automaticTimestamp == null) != (automaticHash == null) || automaticTimestamp != null
                    && (!Double.isFinite(automaticTimestamp) || automaticTimestamp < 0
                    || !automaticHash.matches("[0-9a-f]{64}"))) throw new IllegalArgumentException("Invalid automatic baseline");
            PigmentPreferenceCodec.merge(List.of(), changes); // Validate removals as well as additions.
            changes = Map.copyOf(changes);
        }
        Command(UUID pair, double sourceTimestamp, String baselineHash, Map<String, Boolean> changes) {
            this(pair, sourceTimestamp, baselineHash, changes, null, null);
        }
    }
    private PigmentPreferenceCommand() { }

    static boolean matches(String line) { return line != null && line.startsWith(PREFIX); }

    static String createMirror(PigmentPreferenceMirror.Snapshot current, UUID pair, UUID epoch,
                               double expectedTimestamp, List<String> expectedNames,
                               Map<String, Boolean> changes, long now) {
        if (current == null || current.automatic() == null) throw new IllegalArgumentException("Missing automatic baseline");
        return createMirror(current, pair, epoch, expectedTimestamp, expectedNames,
                current.automatic().sourceTimestamp(), current.automatic().names(), changes, now);
    }

    static String createMirror(PigmentPreferenceMirror.Snapshot current, UUID pair, UUID epoch,
                               double expectedTimestamp, List<String> expectedNames, double automaticTimestamp,
                               List<String> automaticNames, Map<String, Boolean> changes, long now) {
        var command = new Command(pair, expectedTimestamp, hash(PigmentPreferenceCodec.names(expectedNames)), changes,
                automaticTimestamp, hash(PigmentPreferenceCodec.names(automaticNames)));
        requireMirror(command, current, pair, epoch, now);
        PigmentPreferenceCodec.merge(current.value().names(), changes);
        return encode(command);
    }

    static void requireMirror(Command command, PigmentPreferenceMirror.Snapshot current,
                              UUID pair, UUID epoch, long now) {
        if (current == null || !current.manualWritable(now) || !current.pair().equals(pair)
                || !current.epoch().equals(epoch) || !command.pair().equals(pair)
                || Double.compare(command.sourceTimestamp(), current.value().sourceTimestamp()) != 0
                || !command.baselineHash().equals(hash(current.value().names()))
                || command.automaticTimestamp() == null
                || Double.compare(command.automaticTimestamp(), current.automatic().sourceTimestamp()) != 0
                || !command.automaticHash().equals(hash(current.automatic().names()))) {
            throw new IllegalArgumentException("Paired pigment mirror changed before send");
        }
    }

    static String create(PigmentPreferenceObservation.Value current, UUID pair, UUID epoch,
                         double expectedTimestamp, List<String> expectedNames,
                         Map<String, Boolean> changes, long now) {
        requireCurrent(current, pair, epoch, now);
        if (Double.compare(expectedTimestamp, current.sourceTimestamp()) != 0
                || !PigmentPreferenceCodec.sameNames(PigmentPreferenceCodec.names(expectedNames), current.names())) {
            throw new IllegalArgumentException("Pigment baseline changed");
        }
        PigmentPreferenceCodec.merge(current.names(), changes);
        return encode(new Command(pair, expectedTimestamp, hash(current.names()), changes));
    }

    /** Recheck the owned Watch snapshot at the actual send, after local queue delays. */
    static byte[] nativePayload(Command command, PigmentPreferenceObservation.Value current,
                                UUID pair, UUID epoch, long now) {
        requireCurrent(current, pair, epoch, now);
        if (!command.pair().equals(pair)
                || Double.compare(command.sourceTimestamp(), current.sourceTimestamp()) != 0
                || !command.baselineHash().equals(hash(current.names()))) {
            throw new IllegalArgumentException("Pigment baseline changed before send");
        }
        return PigmentPreferenceCodec.encodeChange(
                new PigmentPreferenceCodec.Report(current.names(), current.sourceTimestamp()), command.changes(), now);
    }

    private static void requireCurrent(PigmentPreferenceObservation.Value current, UUID pair, UUID epoch, long now) {
        if (current == null || current.names() == null || pair == null || epoch == null || now <= 0
                || current.observedAt() <= 0
                || !current.pair().equals(pair) || !current.epoch().equals(epoch)
                || current.observedAt() > now || now - current.observedAt() > MAX_BASELINE_AGE) {
            throw new IllegalArgumentException("Current complete pigment observation required");
        }
    }

    private static String hash(List<String> names) {
        // Hash the native set, not the incidental NSSet.allObjects order.
        // Do not reorder stored reports or outgoing NPS values.
        var sorted = new ArrayList<>(PigmentPreferenceCodec.names(names));
        Collections.sort(sorted);
        byte[] value = BinaryPropertyListCodec.encodeStringArray(sorted);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                result[i * 2] = Character.forDigit((digest[i] & 255) >>> 4, 16);
                result[i * 2 + 1] = Character.forDigit(digest[i] & 15, 16);
            }
            return new String(result);
        }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        finally { Arrays.fill(value, (byte) 0); }
    }

    private static String encode(Command command) {
        byte[] bytes = null;
        try {
            var buffer = new ByteArrayOutputStream();
            var out = new DataOutputStream(buffer);
            out.writeInt(MAGIC); out.writeByte(command.automaticTimestamp() == null ? 1 : 2);
            out.writeLong(command.pair().getMostSignificantBits());
            out.writeLong(command.pair().getLeastSignificantBits());
            out.writeDouble(command.sourceTimestamp()); out.writeUTF(command.baselineHash());
            if (command.automaticTimestamp() != null) {
                out.writeDouble(command.automaticTimestamp()); out.writeUTF(command.automaticHash());
            }
            out.writeInt(command.changes().size());
            for (var entry : new TreeMap<>(command.changes()).entrySet()) {
                out.writeUTF(entry.getKey()); out.writeByte(entry.getValue() ? 1 : 0);
                if (buffer.size() > MAX_FRAME) throw new IllegalArgumentException("Pigment delta exceeds limit");
            }
            bytes = buffer.toByteArray();
            return PREFIX + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    static Command parse(String line) {
        if (!matches(line) || line.length() > PREFIX.length() + (MAX_FRAME + 2) / 3 * 4) {
            throw new IllegalArgumentException("Invalid pigment command size");
        }
        byte[] bytes = Base64.getDecoder().decode(line.substring(PREFIX.length()));
        try {
            if (bytes.length > MAX_FRAME || !Base64.getEncoder().encodeToString(bytes).equals(line.substring(PREFIX.length()))) {
                throw new IllegalArgumentException("Noncanonical pigment command");
            }
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("Invalid pigment frame");
            int version = in.readUnsignedByte();
            if (version != 1 && version != 2) throw new IllegalArgumentException("Invalid pigment version");
            UUID pair = new UUID(in.readLong(), in.readLong());
            double timestamp = in.readDouble();
            String hash = in.readUTF();
            Double automaticTimestamp = version == 2 ? in.readDouble() : null;
            String automaticHash = version == 2 ? in.readUTF() : null;
            int count = in.readInt();
            if (count < 1 || count > PigmentPreferenceCodec.MAX_NAMES) throw new IllegalArgumentException("Invalid pigment delta size");
            var changes = new LinkedHashMap<String, Boolean>();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF(); int flag = in.readUnsignedByte();
                if (flag > 1 || changes.putIfAbsent(name, flag == 1) != null) throw new IllegalArgumentException("Invalid pigment delta entry");
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing pigment command data");
            var result = new Command(pair, timestamp, hash, changes, automaticTimestamp, automaticHash);
            if (!encode(result).equals(line)) throw new IllegalArgumentException("Noncanonical pigment delta order");
            return result;
        } catch (IOException truncated) { throw new IllegalArgumentException("Truncated pigment command", truncated); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
}

package dev.applewatchandroid.bridge;

import java.io.*;
import java.util.*;

/** A manual text intent; exact native payload is produced after queue/context recheck. */
final class MonogramPreferenceCommand {
    static final String PREFIX = "NATIVE_MONOGRAM_UPDATE:";
    private static final int MAGIC = 0x4d474331, MAX_FRAME = 256;
    record Command(UUID pair, UUID epoch, String revision, String text) {
        Command {
            if (pair == null || epoch == null || revision == null || !revision.matches("[0-9a-f]{64}")
                    || !NativeMonogramTextRules.valid(text)) throw new IllegalArgumentException("Invalid monogram intent");
        }
    }
    static boolean matches(String line) { return line != null && line.startsWith(PREFIX); }
    static String create(MonogramPreferenceMirror.Snapshot current, UUID pair, UUID epoch, String revision, String text, long now) {
        var command = new Command(pair, epoch, revision, text);
        requireCurrent(command, current, now);
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); MonogramPreferenceMirror.uuid(out, pair); MonogramPreferenceMirror.uuid(out, epoch);
            out.writeUTF(revision); out.writeUTF(text);
        } catch (IOException impossible) { throw new AssertionError(impossible); }
        return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    static void requireCurrent(Command command, MonogramPreferenceMirror.Snapshot current, long now) {
        if (current == null || !current.writable(now) || !command.pair().equals(current.pair())
                || !command.epoch().equals(current.epoch()) || !command.revision().equals(current.revision())) {
            throw new IllegalArgumentException("Monogram target/baseline changed");
        }
    }
    static Command parse(String line) {
        if (!matches(line) || line.length() > PREFIX.length() + (MAX_FRAME + 2) / 3 * 4) throw new IllegalArgumentException("Invalid monogram command size");
        byte[] bytes = Base64.getDecoder().decode(line.substring(PREFIX.length()));
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (bytes.length > MAX_FRAME || !Base64.getEncoder().encodeToString(bytes).equals(line.substring(PREFIX.length()))
                    || in.readInt() != MAGIC) throw new IllegalArgumentException("Invalid monogram command frame");
            var command = new Command(MonogramPreferenceMirror.uuid(in), MonogramPreferenceMirror.uuid(in), in.readUTF(), in.readUTF());
            if (in.available() != 0) throw new IllegalArgumentException("Trailing monogram command");
            return command;
        } catch (IOException invalid) { throw new IllegalArgumentException("Malformed monogram command", invalid); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
}

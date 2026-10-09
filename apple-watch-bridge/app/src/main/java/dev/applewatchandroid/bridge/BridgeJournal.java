package dev.applewatchandroid.bridge;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** UI and foreground service share one rotation/write lock. No payloads belong here. */
final class BridgeJournal {
    private static final Object LOCK = new Object();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd HH:mm:ss.SSS XXX", Locale.US);

    static String entry(String line) {
        return "[" + ZonedDateTime.now().format(TIME) + "] " + line;
    }

    static void append(Context context, String entry) throws IOException {
        synchronized (LOCK) {
            File file = new File(context.getFilesDir(), "bluetooth-runs.log");
            if (file.length() > 5 * 1024 * 1024) {
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                    int keep = 1024 * 1024;
                    raf.seek(raf.length() - keep);
                    byte[] tail = new byte[keep];
                    raf.readFully(tail);
                    raf.setLength(0);
                    raf.seek(0);
                    raf.write(tail);
                }
            }
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write((entry + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}

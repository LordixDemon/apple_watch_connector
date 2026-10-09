package dev.applewatchandroid.bridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Durable journal, bounded UI batching and clipboard; no transport ownership. */
final class BridgeActivityJournal implements AutoCloseable {

    private final Context context;
    private final Handler handler;
    private final java.util.function.Consumer<CharSequence> renderer;
    private volatile boolean closed;
    BridgeActivityJournal(Context context, Handler handler, java.util.function.Consumer<CharSequence> renderer) {
        this.context = context;
        this.handler = handler;
        this.renderer = renderer;
        journalFile = new File(context.getFilesDir(), "bluetooth-runs.log");
    }
    String filePath() { return journalFile.getAbsolutePath(); }
    void clearVisibleLog() {
        synchronized (uiLogLines) { uiLogLines.clear(); }
        handler.post(uiLogUpdater);
    }
    @Override public void close() { closed = true; handler.removeCallbacks(uiLogUpdater); }
    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? context.getString(R.string.no_message) : message;
    }
    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern(
                    "uuuu-MM-dd HH:mm:ss.SSS XXX",
                    Locale.US);

    private final Object journalLock = new Object();

    private static final int MAX_UI_LOG_LINES = 120;

    private final java.util.ArrayDeque<String> uiLogLines = new java.util.ArrayDeque<>();

    private boolean uiLogUpdateScheduled;

    private final Runnable uiLogUpdater = this::render;
    private void render() {
        uiLogUpdateScheduled = false;
        StringBuilder sb = new StringBuilder();
        synchronized (uiLogLines) {
            for (String line : uiLogLines) {
                sb.append(line).append('\n');
            }
        }
        if (!closed) renderer.accept(sb);
    }

    private final File journalFile;

    private volatile boolean journalWriteFailed;
    void copyLog() {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        String text;
        synchronized (journalLock) {
            try {
                if (journalFile.isFile()) {
                    text = readJournal();
                } else {
                    StringBuilder sb = new StringBuilder();
                    synchronized (uiLogLines) {
                        for (String line : uiLogLines) {
                            sb.append(line).append('\n');
                        }
                    }
                    text = sb.toString();
                }
            } catch (IOException error) {
                StringBuilder sb = new StringBuilder();
                synchronized (uiLogLines) {
                    for (String line : uiLogLines) {
                        sb.append(line).append('\n');
                    }
                }
                text = sb.toString();
                Toast.makeText(context,
                        context.getString(R.string.could_not_read_the_full_log_copied_the_current),
                        Toast.LENGTH_LONG).show();
            }
        }
        String bounded = ClipboardLogText.tail(text);
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.ui_log), bounded));
            Toast.makeText(context, context.getString(bounded.length() < text.length()
                    ? R.string.ui_log_recent_copied : R.string.full_log_copied), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException unavailable) {
            Toast.makeText(context, R.string.ui_log_copy_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isHighFrequencyNoise(String line) {
        return line.contains("HCI COMPLETED PACKETS")
                || line.contains("[ERTM RX] supervisory")
                || line.contains("PAIRED NORMAL ERTM RX: kind=S")
                || line.contains("[ERTM TX] RR")
                || line.contains("[NormalLink] push")
                || line.contains("payloadBytes=2; bytes logged=false")
                || line.contains("HCI ACL DATA fragment=");
    }

    void appendLog(String line) {
        String entry = "["
                + ZonedDateTime.now().format(LOG_TIME)
                + "] "
                + line;
        persistLog(entry);
        displayLogEntry(entry);
    }

    void displayLogEntry(String entry) {
        if (!closed && !isHighFrequencyNoise(entry)) {
            synchronized (uiLogLines) {
                if (uiLogLines.size() >= MAX_UI_LOG_LINES) {
                    uiLogLines.removeFirst();
                }
                uiLogLines.addLast(entry);
            }
            handler.post(() -> {
                if (!uiLogUpdateScheduled) {
                    uiLogUpdateScheduled = true;
                    handler.postDelayed(uiLogUpdater, 80);
                }
            });
        }
    }

    private String readJournal() throws IOException {
        try (FileInputStream input = new FileInputStream(journalFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void persistLog(String entry) {
        try {
            BridgeJournal.append(context, entry);
        } catch (IOException error) {
            if (!journalWriteFailed) {
                journalWriteFailed = true;
                handler.post(() -> Toast.makeText(context, context.getString(R.string.bluetooth_log_is_not_being_recorded) + safeMessage(error),
                        Toast.LENGTH_LONG).show());
            }
        }
    }
}

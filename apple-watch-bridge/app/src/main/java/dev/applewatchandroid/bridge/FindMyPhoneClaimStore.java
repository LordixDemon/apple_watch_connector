package dev.applewatchandroid.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.IOException;

/** Private CE storage contains only SHA256 operation fingerprints, times and completion states. */
final class FindMyPhoneClaimStore implements FindMyPhoneClaims.Backend {
    private final SharedPreferences preferences;
    FindMyPhoneClaimStore(Context context) {
        preferences = context.getSharedPreferences("watch_phone_ping_claims", Context.MODE_PRIVATE);
    }
    @Override public byte[] read() throws IOException {
        String frame = preferences.getString("frame", null);
        if (frame == null) return new byte[0];
        if (frame.length() > ((FindMyPhoneClaims.MAX_FRAME + 2) / 3) * 4) throw new IOException("Claims oversized");
        try { return Base64.decode(frame, Base64.NO_WRAP); }
        catch (IllegalArgumentException malformed) { throw new IOException("Invalid claims", malformed); }
    }
    @Override public void write(byte[] bytes) throws IOException {
        if (!preferences.edit().putString("frame", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
            throw new IOException("Phone Ping claims commit failed");
        }
    }
}

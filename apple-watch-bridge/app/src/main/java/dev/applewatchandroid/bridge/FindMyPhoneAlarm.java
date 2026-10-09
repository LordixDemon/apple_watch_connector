package dev.applewatchandroid.bridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.media.*;
import android.os.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Main-looper, bounded phone signal. Does not change global volume or Bluetooth ownership. */
final class FindMyPhoneAlarm implements AutoCloseable {
    private static final int SAMPLE_RATE = 16000, DURATION_MS = 4000;
    private final Context context;
    private final Handler main;
    private final Consumer<String> log;
    private final BiConsumer<Boolean, String> state;
    private final AudioManager audio;
    private final CameraManager cameras;
    private final Map<String, Boolean> torchStates = new HashMap<>();
    private AudioTrack track;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wakeLock;
    private String torchId;
    private String releasedTorchId;
    private boolean torchObserverRegistered;
    private boolean active, closed;
    private final Runnable timeout = () -> stop("bounded timeout");
    private final CameraManager.TorchCallback torchObserver = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean enabled) {
            torchStates.put(id, enabled);
            if (id.equals(torchId)) log.accept("PHONE FIND TORCH observed enabled=" + enabled);
            if (id.equals(releasedTorchId) && !enabled) {
                releasedTorchId = null;
                log.accept("PHONE FIND TORCH observed enabled=false after release.");
            }
        }
        @Override public void onTorchModeUnavailable(String id) { torchStates.remove(id); }
    };
    FindMyPhoneAlarm(Context context, Handler main, Consumer<String> log, BiConsumer<Boolean, String> state) {
        this.context = context; this.main = main; this.log = log; this.state = state;
        audio = context.getSystemService(AudioManager.class);
        cameras = context.getSystemService(CameraManager.class);
        if (cameras != null) {
            try { cameras.registerTorchCallback(torchObserver, main); torchObserverRegistered = true; }
            catch (RuntimeException unavailable) { log.accept("PHONE FIND torch observer unavailable; sound remains available."); }
        }
    }
    boolean active() { return active; }
    boolean start(int behavior) {
        requireMain();
        if (closed || behavior < 0 || behavior > 4) return false;
        if (behavior == 4) return true; // Native None acknowledges without sound or torch.
        if (active) { log.accept("PHONE FIND signal busy; previous signal retained."); return false; }
        boolean sound = behavior != 2, flash = behavior == 1 || behavior == 2;
        try {
            if (flash && !enableTorch()) return false;
            wakeLock = context.getSystemService(PowerManager.class).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "applewatchbridge:phone-ping");
            wakeLock.acquire(DURATION_MS + 1500L);
            if (sound) {
                AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(change -> {
                            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                                stop("audio focus lost");
                            }
                        }, main).build();
                if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    stop("audio focus unavailable"); return false;
                }
                short[] pcm = createSignal(behavior == 3);
                try {
                    track = new AudioTrack.Builder().setAudioAttributes(attributes)
                            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
                    // MODE_STATIC is STATE_NO_STATIC_DATA until the first successful write.
                    if (track.getState() == AudioTrack.STATE_UNINITIALIZED
                            || track.write(pcm, 0, pcm.length) != pcm.length
                            || track.getState() != AudioTrack.STATE_INITIALIZED) {
                        stop("PCM buffer rejected"); return false;
                    }
                    AudioDeviceInfo speaker = Arrays.stream(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                            .filter(device -> device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).findFirst().orElse(null);
                    if (speaker == null || !track.setPreferredDevice(speaker)) {
                        stop("phone speaker route unavailable"); return false;
                    }
                    track.setVolume(0.8f);
                    track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                        @Override public void onMarkerReached(AudioTrack source) {
                            if (source == track) stop("PCM completed");
                        }
                        @Override public void onPeriodicNotification(AudioTrack source) { }
                    }, main);
                    track.setNotificationMarkerPosition(pcm.length);
                    track.play();
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        stop("audio playback not started"); return false;
                    }
                    log.accept("PHONE FIND AudioTrack started; alarmVolume=" + audio.getStreamVolume(AudioManager.STREAM_ALARM)
                            + "; volume unchanged; durationMs=" + DURATION_MS + "; audible effect not inferred.");
                    AudioTrack started = track;
                    main.postDelayed(() -> {
                        if (track == started) {
                            AudioDeviceInfo route = track.getRoutedDevice();
                            log.accept("PHONE FIND AUDIO observed frames=" + track.getPlaybackHeadPosition()
                                    + " routedType=" + (route == null ? -1 : route.getType()));
                        }
                    }, 200);
                } finally { Arrays.fill(pcm, (short) 0); }
            }
            active = true;
            main.postDelayed(timeout, DURATION_MS + 500L);
            state.accept(true, "started");
            return true;
        } catch (RuntimeException failure) {
            log.accept("PHONE FIND signal unavailable: " + failure.getClass().getSimpleName());
            stop("start failure"); return false;
        }
    }
    private boolean enableTorch() {
        if (cameras == null || context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            log.accept("PHONE FIND torch unavailable: camera permission missing."); return false;
        }
        try {
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics characteristics = cameras.getCameraCharacteristics(id);
                if (Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
                        && Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(
                                characteristics.get(CameraCharacteristics.LENS_FACING))
                        && Boolean.FALSE.equals(torchStates.get(id))) {
                    cameras.setTorchMode(id, true);
                    torchId = id;
                    return true;
                }
            }
            log.accept("PHONE FIND torch unavailable: no idle rear flash.");
        } catch (CameraAccessException | SecurityException failure) {
            log.accept("PHONE FIND torch unavailable: " + failure.getClass().getSimpleName());
        }
        return false;
    }
    void stop(String reason) {
        requireMain();
        main.removeCallbacks(timeout);
        boolean wasActive = active;
        boolean ownedResource = track != null || focus != null || torchId != null || wakeLock != null;
        active = false;
        if (track != null) {
            AudioTrack previous = track; track = null;
            try { previous.stop(); } catch (IllegalStateException ignored) { }
            previous.release();
        }
        if (focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
        if (torchId != null) {
            String previous = torchId; torchId = null; releasedTorchId = previous;
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                try { cameras.setTorchMode(previous, false); }
                catch (CameraAccessException | SecurityException ignored) { }
            }
            log.accept("PHONE FIND torch release requested.");
        }
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
        if (wasActive || ownedResource) log.accept("PHONE FIND signal stopped: " + reason);
        if (wasActive) state.accept(false, reason);
    }
    static short[] createSignal(boolean nearby) {
        short[] pcm = new short[SAMPLE_RATE * DURATION_MS / 1000];
        for (int i = 0; i < pcm.length; i++) {
            int phase = i % (SAMPLE_RATE / 2), toneLength = SAMPLE_RATE / 4;
            if (phase >= toneLength) continue;
            double ramp = Math.min(1.0, Math.min(phase, toneLength - phase) / 160.0);
            double frequency = nearby ? 1400 : (i / (SAMPLE_RATE / 2)) % 2 == 0 ? 1000 : 1250;
            pcm[i] = (short) (18000 * ramp * Math.sin(2 * Math.PI * frequency * i / SAMPLE_RATE));
        }
        return pcm;
    }
    private void requireMain() {
        if (Looper.myLooper() != main.getLooper()) throw new IllegalStateException("Phone Ping requires main looper");
    }
    @Override public void close() {
        stop("service destroyed"); closed = true;
        if (torchObserverRegistered) cameras.unregisterTorchCallback(torchObserver);
    }
}

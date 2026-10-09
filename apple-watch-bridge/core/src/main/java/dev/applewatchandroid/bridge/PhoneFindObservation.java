package dev.applewatchandroid.bridge;

/** Phone-owned effect observation; never evidence of a Watch-side applied command. */
record PhoneFindObservation(boolean active, int behavior, boolean localProbe,
                            boolean didPlay, long observedAt, String stopReason) {
    PhoneFindObservation {
        if (behavior < 0 || behavior > 4 || observedAt <= 0
                || (active && (!didPlay || behavior == 4))
                || (stopReason != null && (active || stopReason.length() > 128))) {
            throw new IllegalArgumentException("Invalid phone signal observation");
        }
    }
    PhoneFindObservation stopped(long time, String reason) {
        return new PhoneFindObservation(false, behavior, localProbe, didPlay, time, reason);
    }
}

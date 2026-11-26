package dev.applewatchandroid.bridge;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HealthKit {@code HKWorkoutActivityType} display catalog and read-only
 * {@code HDCodableWorkout} summary extractor.
 *
 * <p>Field tags are verified directly against Apple's {@code HealthDaemon}
 * {@code HDCodableWorkout} protobuf serialization. Unknown activity codes stay
 * numeric ({@code "Type <id>"}).</p>
 */
final class NativeHealthWorkoutCatalog {
    static final long ACTIVITY_WALKING = 52L;
    private static final String KEY_INDOOR_WORKOUT = "HKIndoorWorkout";

    private static final Map<Long, String> ACTIVITIES;

    static {
        Map<Long, String> map = new HashMap<>();
        map.put(1L, "American Football");
        map.put(2L, "Archery");
        map.put(3L, "Australian Football");
        map.put(4L, "Badminton");
        map.put(5L, "Baseball");
        map.put(6L, "Basketball");
        map.put(7L, "Bowling");
        map.put(8L, "Boxing");
        map.put(9L, "Climbing");
        map.put(10L, "Cricket");
        map.put(11L, "Cross Training");
        map.put(12L, "Curling");
        map.put(13L, "Cycling");
        map.put(14L, "Dance");
        map.put(15L, "Dance Inspired Training");
        map.put(16L, "Elliptical");
        map.put(17L, "Equestrian Sports");
        map.put(18L, "Fencing");
        map.put(19L, "Fishing");
        map.put(20L, "Functional Strength Training");
        map.put(21L, "Golf");
        map.put(22L, "Gymnastics");
        map.put(23L, "Handball");
        map.put(24L, "Hiking");
        map.put(25L, "Hockey");
        map.put(26L, "Hunting");
        map.put(27L, "Lacrosse");
        map.put(28L, "Martial Arts");
        map.put(29L, "Mind and Body");
        map.put(30L, "Mixed Metabolic Cardio Training");
        map.put(31L, "Paddle Sports");
        map.put(32L, "Play");
        map.put(33L, "Preparation And Recovery");
        map.put(34L, "Racquetball");
        map.put(35L, "Rowing");
        map.put(36L, "Rugby");
        map.put(37L, "Running");
        map.put(38L, "Sailing");
        map.put(39L, "Skating Sports");
        map.put(40L, "Snow Sports");
        map.put(41L, "Soccer");
        map.put(42L, "Softball");
        map.put(43L, "Squash");
        map.put(44L, "Stair Climbing");
        map.put(45L, "Surfing Sports");
        map.put(46L, "Swimming");
        map.put(47L, "Table Tennis");
        map.put(48L, "Tennis");
        map.put(49L, "Track and Field");
        map.put(50L, "Traditional Strength Training");
        map.put(51L, "Volleyball");
        map.put(52L, "Walking");
        map.put(53L, "Water Fitness");
        map.put(54L, "Water Polo");
        map.put(55L, "Water Sports");
        map.put(56L, "Wrestling");
        map.put(57L, "Yoga");
        map.put(58L, "Barre");
        map.put(59L, "Core Training");
        map.put(60L, "Cross Country Skiing");
        map.put(61L, "Downhill Skiing");
        map.put(62L, "Flexibility");
        map.put(63L, "HIIT");
        map.put(64L, "Jump Rope");
        map.put(65L, "Kickboxing");
        map.put(66L, "Pilates");
        map.put(67L, "Snowboarding");
        map.put(68L, "Stairs");
        map.put(69L, "Step Training");
        map.put(70L, "Wheelchair Walk Pace");
        map.put(71L, "Wheelchair Run Pace");
        map.put(72L, "Tai Chi");
        map.put(73L, "Mixed Cardio");
        map.put(74L, "Hand Cycling");
        map.put(75L, "Disc Sports");
        map.put(76L, "Fitness Gaming");
        map.put(77L, "Cardio Dance");
        map.put(78L, "Social Dance");
        map.put(79L, "Pickleball");
        map.put(80L, "Cooldown");
        map.put(82L, "Swim Bike Run");
        map.put(83L, "Transition");
        map.put(84L, "Underwater Diving");
        map.put(3000L, "Other");
        ACTIVITIES = Collections.unmodifiableMap(map);
    }

    private NativeHealthWorkoutCatalog() {
    }

    record Summary(
            NativeHealthObjectCodec.SampleHeader sample,
            Long activityType,
            String activityLabel,
            Double durationSeconds,
            Double totalEnergyBurnedKcal,
            Double totalDistanceMeters,
            Double totalBasalEnergyBurnedKcal) {
        @Override
        public String toString() {
            return "Health workout summary (values withheld)";
        }
    }

    static String label(Long activityType, NativeHealthChangesCodec.Node metadataDictionary) {
        if (activityType == null) {
            return "Not reported";
        }
        String base = ACTIVITIES.getOrDefault(activityType, "Type " + activityType);
        if (activityType != ACTIVITY_WALKING || metadataDictionary == null
                || metadataDictionary.schema != NativeHealthChangesCodec.Schema.METADATA_DICTIONARY) {
            return base;
        }
        Boolean indoor = null;
        for (NativeHealthChangesCodec.Node pair : metadataDictionary.children(1)) {
            if (!KEY_INDOOR_WORKOUT.equals(pair.string(1))) {
                continue;
            }
            if (pair.string(2) != null || pair.doubleValue(3) != null
                    || !pair.children(6).isEmpty() || pair.bytes(7) != null) {
                return base;
            }
            Long intVal = pair.int64(4);
            Double dblVal = pair.doubleValue(5);
            if ((intVal == null) == (dblVal == null)) {
                return base;
            }
            boolean flag;
            if (intVal != null) {
                if (intVal != 0L && intVal != 1L) return base;
                flag = intVal == 1L;
            } else {
                if (!Double.isFinite(dblVal) || (dblVal != 0.0 && dblVal != 1.0)) return base;
                flag = dblVal == 1.0;
            }
            if (indoor != null) {
                return base;
            }
            indoor = flag;
        }
        if (indoor == null) {
            return base;
        }
        return indoor ? "Indoor Walk" : "Outdoor Walk";
    }

    static Summary summarize(NativeHealthChangesCodec.Node workout) {
        if (workout == null || workout.schema != NativeHealthChangesCodec.Schema.WORKOUT) {
            throw new IllegalArgumentException("Expected Health workout record");
        }
        List<NativeHealthChangesCodec.Node> samples = workout.children(1);
        NativeHealthObjectCodec.SampleHeader header = null;
        NativeHealthChangesCodec.Node metadata = null;
        if (!samples.isEmpty()) {
            NativeHealthChangesCodec.Node sample = samples.get(0);
            header = NativeHealthObjectCodec.header(sample);
            List<NativeHealthChangesCodec.Node> objects = sample.children(1);
            if (!objects.isEmpty()) {
                List<NativeHealthChangesCodec.Node> dicts = objects.get(0).children(2);
                if (!dicts.isEmpty()) {
                    metadata = dicts.get(0);
                }
            }
        }
        Long activityType = workout.int64(2);
        if (activityType != null && activityType < 0L) {
            throw new IllegalArgumentException("Invalid negative workout activity type");
        }
        Double duration = requireNonNegativeFinite(workout.doubleValue(4), "duration");
        Double energy = requireNonNegativeFinite(workout.doubleValue(5), "energy");
        Double distance = requireNonNegativeFinite(workout.doubleValue(6), "distance");
        Double basalEnergy = requireNonNegativeFinite(workout.doubleValue(9), "basal energy");
        return new Summary(
                header,
                activityType,
                label(activityType, metadata),
                duration,
                energy,
                distance,
                basalEnergy);
    }

    private static Double requireNonNegativeFinite(Double value, String name) {
        if (value == null) {
            return null;
        }
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Invalid workout " + name);
        }
        return value;
    }
}

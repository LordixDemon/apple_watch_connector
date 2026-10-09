package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Secure NSKeyedArchiver payload carried by PBBridge message type 25.
 *
 * <p>iOS 26.6 obtains the dictionary from {@code NSLocale
 * archivedPreferences}. The Watch decodes it under
 * {@code PBBridgeLocalePreferencesKey} and admits exactly NSDictionary,
 * NSArray, NSMutableArray, NSString, NSNumber, and NSData. Fresh setup only
 * requires non-empty AppleLanguages and AppleLocale; the remaining keys are
 * optional preferences.</p>
 */
final class LocalePreferencesArchiveCodec {
    static final String TOP_KEY =
            "PBBridgeLocalePreferencesKey";
    static final String APPLE_LANGUAGES =
            "AppleLanguages";
    static final String APPLE_LOCALE =
            "AppleLocale";
    static final String APPLE_TEMPERATURE_UNIT =
            "AppleTemperatureUnit";
    static final String FORCE_12_HOUR =
            "AppleICUForce12HourTime";
    static final String FORCE_24_HOUR =
            "AppleICUForce24HourTime";
    static final String ARCHIVED_INFLECTION =
            "ArchivedInflection";

    private static final String ARCHIVER =
            "NSKeyedArchiver";
    private static final long ARCHIVE_VERSION =
            100000L;
    private static final int MAX_ARCHIVE_LENGTH =
            1024 * 1024;
    private static final int MAX_LANGUAGES =
            64;
    private static final int MAX_STRING_LENGTH =
            64 * 1024;
    private static final Set<String> KNOWN_KEYS =
            Set.of(
                    APPLE_LANGUAGES,
                    APPLE_LOCALE,
                    APPLE_TEMPERATURE_UNIT,
                    FORCE_12_HOUR,
                    FORCE_24_HOUR,
                    ARCHIVED_INFLECTION);

    private LocalePreferencesArchiveCodec() {
    }

    /**
     * Creates the minimal modern Setup dictionary accepted by
     * {@code +[NSLocale setArchivedPreferences:]}.
     */
    static byte[] encodeSetupPreferences(
            List<String> appleLanguages,
            String appleLocale) {
        List<String> languages =
                validatedLanguages(
                        appleLanguages);
        requireString(
                appleLocale,
                false,
                "Apple locale");

        List<Object> objects =
                new ArrayList<>();
        objects.add(
                "$null");
        objects.add(
                null);
        objects.add(
                APPLE_LOCALE);
        objects.add(
                APPLE_LANGUAGES);
        objects.add(
                appleLocale);

        int arrayIndex =
                objects.size();
        objects.add(
                null);
        List<Object> languageReferences =
                new ArrayList<>(
                        languages.size());
        for (String language :
                languages) {
            languageReferences.add(
                    uid(
                            objects.size()));
            objects.add(
                    language);
        }

        int arrayClassIndex =
                objects.size();
        objects.add(
                classDescriptor(
                        "NSArray"));
        int dictionaryClassIndex =
                objects.size();
        objects.add(
                classDescriptor(
                        "NSDictionary"));

        LinkedHashMap<String, Object> array =
                new LinkedHashMap<>();
        array.put(
                "NS.objects",
                languageReferences);
        array.put(
                "$class",
                uid(
                        arrayClassIndex));
        objects.set(
                arrayIndex,
                array);

        LinkedHashMap<String, Object> dictionary =
                new LinkedHashMap<>();
        dictionary.put(
                "NS.keys",
                List.of(
                        uid(
                                2),
                        uid(
                                3)));
        dictionary.put(
                "NS.objects",
                List.of(
                        uid(
                                4),
                        uid(
                                arrayIndex)));
        dictionary.put(
                "$class",
                uid(
                        dictionaryClassIndex));
        objects.set(
                1,
                dictionary);

        LinkedHashMap<String, Object> top =
                new LinkedHashMap<>();
        top.put(
                TOP_KEY,
                uid(
                        1));
        LinkedHashMap<String, Object> root =
                new LinkedHashMap<>();
        root.put(
                "$version",
                ARCHIVE_VERSION);
        root.put(
                "$archiver",
                ARCHIVER);
        root.put(
                "$top",
                top);
        root.put(
                "$objects",
                objects);
        byte[] encoded =
                AppleBinaryPropertyList.encode(
                        root);
        if (encoded.length == 0
                || encoded.length > MAX_ARCHIVE_LENGTH) {
            wipe(
                    encoded);
            throw new IllegalArgumentException(
                    "Locale preferences archive length is invalid");
        }
        return encoded;
    }

    static Preferences decode(
            byte[] encoded) {
        if (encoded == null
                || encoded.length == 0
                || encoded.length > MAX_ARCHIVE_LENGTH) {
            throw new IllegalArgumentException(
                    "Locale preferences archive length is invalid");
        }
        Map<String, Object> root =
                requireDictionary(
                        AppleBinaryPropertyList.decode(
                                encoded),
                        "archive root");
        if (root.size() != 4
                || !ARCHIVER.equals(
                        root.get(
                                "$archiver"))
                || !(root.get(
                        "$version") instanceof Long version)
                || version != ARCHIVE_VERSION) {
            throw new IllegalArgumentException(
                    "Locale NSKeyedArchiver metadata is invalid");
        }
        List<Object> objects =
                requireList(
                        root.get(
                                "$objects"),
                        "archive object table");
        if (objects.size() < 2
                || !"$null".equals(
                        objects.get(
                                0))) {
            throw new IllegalArgumentException(
                    "Locale archive null object is invalid");
        }
        Map<String, Object> top =
                requireDictionary(
                        root.get(
                                "$top"),
                        "archive top");
        if (top.size() != 1
                || !top.containsKey(
                        TOP_KEY)) {
            throw new IllegalArgumentException(
                    "Locale archive top key is invalid");
        }
        Map<String, Object> dictionary =
                requireObjectDictionary(
                        objects,
                        top.get(
                                TOP_KEY),
                        "locale preferences dictionary");
        requireClass(
                objects,
                dictionary.get(
                        "$class"),
                Set.of(
                        "NSDictionary"),
                "NSDictionary");
        List<Object> keyReferences =
                requireList(
                        dictionary.get(
                                "NS.keys"),
                        "locale preference keys");
        List<Object> valueReferences =
                requireList(
                        dictionary.get(
                                "NS.objects"),
                        "locale preference values");
        if (dictionary.size() != 3
                || keyReferences.size()
                != valueReferences.size()
                || keyReferences.size() < 2
                || keyReferences.size()
                > KNOWN_KEYS.size()) {
            throw new IllegalArgumentException(
                    "Locale preference dictionary size is invalid");
        }

        List<String> languages = null;
        String locale = null;
        String temperatureUnit = null;
        Boolean force12Hour = null;
        Boolean force24Hour = null;
        byte[] archivedInflection = null;
        Set<String> seen =
                new HashSet<>();
        try {
            for (int index = 0;
                    index < keyReferences.size();
                    index++) {
                String key =
                        requireStringObject(
                                objects,
                                keyReferences.get(
                                        index),
                                "locale preference key");
                if (!KNOWN_KEYS.contains(
                        key)
                        || !seen.add(
                                key)) {
                    throw new IllegalArgumentException(
                            "Locale archive has an unknown or duplicate key");
                }
                Object reference =
                        valueReferences.get(
                                index);
                switch (key) {
                    case APPLE_LANGUAGES ->
                            languages =
                                    decodeLanguages(
                                            objects,
                                            reference);
                    case APPLE_LOCALE -> {
                        locale =
                                requireStringObject(
                                        objects,
                                        reference,
                                        "Apple locale");
                        requireString(
                                locale,
                                false,
                                "Apple locale");
                    }
                    case APPLE_TEMPERATURE_UNIT -> {
                        temperatureUnit =
                                requireStringObject(
                                        objects,
                                        reference,
                                        "Apple temperature unit");
                        requireString(
                                temperatureUnit,
                                false,
                                "Apple temperature unit");
                    }
                    case FORCE_12_HOUR ->
                            force12Hour =
                                    requireBooleanObject(
                                            objects,
                                            reference,
                                            "12-hour preference");
                    case FORCE_24_HOUR ->
                            force24Hour =
                                    requireBooleanObject(
                                            objects,
                                            reference,
                                            "24-hour preference");
                    case ARCHIVED_INFLECTION -> {
                        Object value =
                                resolveObject(
                                        objects,
                                        reference,
                                        "archived inflection");
                        if (!(value instanceof byte[] bytes)
                                || bytes.length == 0
                                || bytes.length > MAX_ARCHIVE_LENGTH) {
                            throw new IllegalArgumentException(
                                    "Archived inflection is invalid");
                        }
                        archivedInflection =
                                bytes.clone();
                    }
                    default -> throw new IllegalArgumentException(
                            "Unsupported locale preference key");
                }
            }
            if (languages == null
                    || locale == null) {
                throw new IllegalArgumentException(
                        "Required locale preferences are absent");
            }
            Preferences result =
                    new Preferences(
                            languages,
                            locale,
                            temperatureUnit,
                            force12Hour,
                            force24Hour,
                            archivedInflection);
            wipe(
                    archivedInflection);
            return result;
        } catch (RuntimeException failure) {
            wipe(
                    archivedInflection);
            throw failure;
        }
    }

    private static List<String> decodeLanguages(
            List<Object> objects,
            Object reference) {
        Map<String, Object> array =
                requireObjectDictionary(
                        objects,
                        reference,
                        "Apple languages");
        requireClass(
                objects,
                array.get(
                        "$class"),
                Set.of(
                        "NSArray",
                        "NSMutableArray"),
                "NSArray");
        if (array.size() != 2) {
            throw new IllegalArgumentException(
                    "Archived Apple languages array is invalid");
        }
        List<Object> references =
                requireList(
                        array.get(
                                "NS.objects"),
                        "Apple language references");
        List<String> languages =
                new ArrayList<>(
                        references.size());
        for (Object item :
                references) {
            languages.add(
                    requireStringObject(
                            objects,
                            item,
                            "Apple language"));
        }
        return validatedLanguages(
                languages);
    }

    private static List<String> validatedLanguages(
            List<String> source) {
        if (source == null
                || source.isEmpty()
                || source.size() > MAX_LANGUAGES) {
            throw new IllegalArgumentException(
                    "Apple languages are invalid");
        }
        List<String> result =
                new ArrayList<>(
                        source.size());
        Set<String> unique =
                new HashSet<>();
        for (String language :
                source) {
            requireString(
                    language,
                    false,
                    "Apple language");
            if (!unique.add(
                    language)) {
                throw new IllegalArgumentException(
                        "Apple languages contain a duplicate");
            }
            result.add(
                    language);
        }
        return Collections.unmodifiableList(
                result);
    }

    private static void requireString(
            String value,
            boolean allowEmpty,
            String label) {
        if (value == null
                || (!allowEmpty && value.isEmpty())
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    label + " is invalid");
        }
        byte[] utf8 =
                value.getBytes(
                        StandardCharsets.UTF_8);
        try {
            if (utf8.length > MAX_STRING_LENGTH
                    || !value.equals(
                            new String(
                                    utf8,
                                    StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException(
                        label + " is invalid");
            }
        } finally {
            wipe(
                    utf8);
        }
    }

    private static void requireClass(
            List<Object> objects,
            Object reference,
            Set<String> allowedNames,
            String requiredBaseClass) {
        Map<String, Object> descriptor =
                requireObjectDictionary(
                        objects,
                        reference,
                        "class descriptor");
        if (descriptor.size() != 2
                || !(descriptor.get(
                        "$classname") instanceof String name)
                || !allowedNames.contains(
                        name)) {
            throw new IllegalArgumentException(
                    "Locale archive class descriptor is invalid");
        }
        List<Object> hierarchy =
                requireList(
                        descriptor.get(
                                "$classes"),
                        "class hierarchy");
        if (!hierarchy.contains(
                requiredBaseClass)
                || !hierarchy.contains(
                "NSObject")) {
            throw new IllegalArgumentException(
                    "Locale archive class hierarchy is invalid");
        }
        for (Object item :
                hierarchy) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException(
                        "Locale archive class hierarchy is malformed");
            }
        }
    }

    private static Map<String, Object> classDescriptor(
            String className) {
        LinkedHashMap<String, Object> descriptor =
                new LinkedHashMap<>();
        descriptor.put(
                "$classname",
                className);
        descriptor.put(
                "$classes",
                List.of(
                        className,
                        "NSObject"));
        return descriptor;
    }

    private static AppleBinaryPropertyList.Uid uid(
            int index) {
        return new AppleBinaryPropertyList.Uid(
                index);
    }

    private static String requireStringObject(
            List<Object> objects,
            Object reference,
            String label) {
        Object value =
                resolveObject(
                        objects,
                        reference,
                        label);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(
                    label + " is not a string");
        }
        requireString(
                string,
                true,
                label);
        return string;
    }

    private static Boolean requireBooleanObject(
            List<Object> objects,
            Object reference,
            String label) {
        Object value =
                resolveObject(
                        objects,
                        reference,
                        label);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Long longValue
                && (longValue == 0
                || longValue == 1)) {
            return longValue == 1;
        }
        throw new IllegalArgumentException(
                label + " is not boolean");
    }

    private static Object resolveObject(
            List<Object> objects,
            Object reference,
            String label) {
        if (!(reference instanceof AppleBinaryPropertyList.Uid uid)
                || uid.value() < 0
                || uid.value() >= objects.size()) {
            throw new IllegalArgumentException(
                    label + " reference is invalid");
        }
        return objects.get(
                uid.value());
    }

    private static Map<String, Object> requireObjectDictionary(
            List<Object> objects,
            Object reference,
            String label) {
        return requireDictionary(
                resolveObject(
                        objects,
                        reference,
                        label),
                label);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireDictionary(
            Object value,
            String label) {
        if (!(value instanceof Map<?, ?> dictionary)) {
            throw new IllegalArgumentException(
                    label + " is not a dictionary");
        }
        for (Object key :
                dictionary.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(
                        label + " has a non-string key");
            }
        }
        return (Map<String, Object>) dictionary;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requireList(
            Object value,
            String label) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    label + " is not an array");
        }
        return (List<Object>) list;
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    static final class Preferences {
        private final List<String> appleLanguages;
        private final String appleLocale;
        private final String appleTemperatureUnit;
        private final Boolean force12Hour;
        private final Boolean force24Hour;
        private byte[] archivedInflection;
        private boolean destroyed;

        private Preferences(
                List<String> appleLanguages,
                String appleLocale,
                String appleTemperatureUnit,
                Boolean force12Hour,
                Boolean force24Hour,
                byte[] archivedInflection) {
            this.appleLanguages =
                    appleLanguages;
            this.appleLocale =
                    appleLocale;
            this.appleTemperatureUnit =
                    appleTemperatureUnit;
            this.force12Hour =
                    force12Hour;
            this.force24Hour =
                    force24Hour;
            this.archivedInflection =
                    archivedInflection == null
                            ? null
                            : archivedInflection.clone();
        }

        List<String> appleLanguages() {
            requireValid();
            return appleLanguages;
        }

        String appleLocale() {
            requireValid();
            return appleLocale;
        }

        String appleTemperatureUnit() {
            requireValid();
            return appleTemperatureUnit;
        }

        Boolean force12Hour() {
            requireValid();
            return force12Hour;
        }

        Boolean force24Hour() {
            requireValid();
            return force24Hour;
        }

        byte[] archivedInflection() {
            requireValid();
            return archivedInflection == null
                    ? null
                    : archivedInflection.clone();
        }

        void destroy() {
            wipe(
                    archivedInflection);
            archivedInflection = null;
            destroyed = true;
        }

        private void requireValid() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Locale preferences were destroyed");
            }
        }
    }
}

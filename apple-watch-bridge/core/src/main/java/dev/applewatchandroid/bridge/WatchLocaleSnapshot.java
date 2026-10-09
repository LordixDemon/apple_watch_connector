package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.List;

/** Values observed on the authenticated Watch, never phone/app locale defaults. */
record WatchLocaleSnapshot(List<String> languages, String locale) {
    WatchLocaleSnapshot {
        languages = List.copyOf(languages);
        byte[] checked = LocalePreferencesArchiveCodec.encodeSetupPreferences(languages, locale);
        Arrays.fill(checked, (byte) 0);
    }

    static WatchLocaleSnapshot fromProperties(NanoRegistryPropertyCodec.PropertyValue languages,
            NanoRegistryPropertyCodec.PropertyValue locale) {
        if (languages == null || locale == null || languages.arrayValues == null
                || languages.arrayValues.isEmpty() || languages.arrayValues.size() > 64
                || languages.isError || locale.isError || languages.isSet
                || languages.dictionaryKey != null || locale.dictionaryKey != null
                || locale.stringValue == null || locale.stringValue.isBlank()) {
            throw new IllegalArgumentException("Watch language/locale observation is missing or invalid");
        }
        List<String> tags = languages.arrayValues.stream().map(value -> {
            if (value == null || value.isError || value.dictionaryKey != null
                    || value.stringValue == null || value.stringValue.isBlank()) {
                throw new IllegalArgumentException("Watch preferred language is not a string");
            }
            return value.stringValue;
        }).toList();
        return new WatchLocaleSnapshot(tags, locale.stringValue);
    }
}

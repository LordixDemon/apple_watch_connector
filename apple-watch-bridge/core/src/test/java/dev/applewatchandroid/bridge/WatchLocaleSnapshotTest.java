package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WatchLocaleSnapshotTest {
    @Test public void preservesWatchLanguageOrderAndRegionDespiteRussianPhoneLocale() {
        Locale original = Locale.getDefault();
        byte[] archive = null;
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"));
            var input = new ArrayList<>(List.of("de-DE", "en-GB"));
            var observed = new WatchLocaleSnapshot(input, "de_AT");
            input.clear();
            archive = LocalePreferencesArchiveCodec.encodeSetupPreferences(observed.languages(), observed.locale());
            var decoded = LocalePreferencesArchiveCodec.decode(archive);
            assertEquals(List.of("de-DE", "en-GB"), decoded.appleLanguages());
            assertEquals("de_AT", decoded.appleLocale());
            assertThrows(UnsupportedOperationException.class, () -> observed.languages().add("ru-RU"));
        } finally {
            if (archive != null) Arrays.fill(archive, (byte) 0);
            Locale.setDefault(original);
        }
    }
    @Test public void missingMalformedAndUnorderedObservationsHaveNoPhoneFallback() {
        var tag = NanoRegistryPropertyCodec.PropertyValue.string("ja-JP");
        var tags = NanoRegistryPropertyCodec.PropertyValue.array(List.of(tag));
        var locale = NanoRegistryPropertyCodec.PropertyValue.string("ja_JP");
        assertEquals(new WatchLocaleSnapshot(List.of("ja-JP"), "ja_JP"), WatchLocaleSnapshot.fromProperties(tags, locale));
        assertThrows(IllegalArgumentException.class, () -> WatchLocaleSnapshot.fromProperties(null, locale));
        assertThrows(IllegalArgumentException.class, () -> WatchLocaleSnapshot.fromProperties(tags, null));
        assertThrows(IllegalArgumentException.class, () -> WatchLocaleSnapshot.fromProperties(
                NanoRegistryPropertyCodec.PropertyValue.set(List.of(tag)), locale));
        assertThrows(IllegalArgumentException.class, () -> WatchLocaleSnapshot.fromProperties(
                NanoRegistryPropertyCodec.PropertyValue.array(List.of(NanoRegistryPropertyCodec.PropertyValue.data(new byte[]{1}))), locale));
    }
}

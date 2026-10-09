package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.Test;

public final class LocalePreferencesArchiveCodecTest {
    private static final String FOUNDATION_FIXTURE =
            "YnBsaXN0MDDUAQIDBAUGBwpYJHZlcnNpb25ZJGFyY2hpdmVyVCR0b3BYJG9i"
                    + "amVjdHMSAAGGoF8QD05TS2V5ZWRBcmNoaXZlctEICV8QHFBCQnJpZGdlTG9j"
                    + "YWxlUHJlZmVyZW5jZXNLZXmAAaoLDBcYGRofICEnVSRudWxs0w0ODxATFldO"
                    + "Uy5rZXlzWk5TLm9iamVjdHNWJGNsYXNzohESgAKAA6IUFYAEgAWACVtBcHBs"
                    + "ZUxvY2FsZV5BcHBsZUxhbmd1YWdlc1V1a19VQdIODxseohwdgAaAB4AIVWVu"
                    + "LVVTVXVrLVVB0iIjJCVaJGNsYXNzbmFtZVgkY2xhc3Nlc1dOU0FycmF5oiQm"
                    + "WE5TT2JqZWN00iIjKClcTlNEaWN0aW9uYXJ5oigmAAgAEQAaACQAKQAyADcA"
                    + "SQBMAGsAbQB4AH4AhQCNAJgAnwCiAKQApgCpAKsArQCvALsAygDQANUA2ADa"
                    + "ANwA3gDkAOoA7wD6AQMBCwEOARcBHAEpAAAAAAAAAgEAAAAAAAAAKgAAAAAA"
                    + "AAAAAAAAAAAAASw=";

    @Test
    public void minimalArchiveRoundTripsAndFeedsTypeTwentyFive() {
        byte[] archive =
                LocalePreferencesArchiveCodec.encodeSetupPreferences(
                        List.of(
                                "en-US",
                                "uk-UA"),
                        "uk_UA");
        LocalePreferencesArchiveCodec.Preferences decoded =
                LocalePreferencesArchiveCodec.decode(
                        archive);
        PbBridgeCodec.LanguageAndLocale message =
                new PbBridgeCodec.LanguageAndLocale(
                        decoded.appleLanguages(),
                        decoded.appleLocale(),
                        archive);
        byte[] protobuf =
                PbBridgeCodec.encode(
                        message);
        try {
            assertArrayEquals(
                    "bplist00".getBytes(
                            StandardCharsets.US_ASCII),
                    Arrays.copyOf(
                            archive,
                            8));
            assertEquals(
                    List.of(
                            "en-US",
                            "uk-UA"),
                    decoded.appleLanguages());
            assertEquals(
                    "uk_UA",
                    decoded.appleLocale());
            assertNull(
                    decoded.appleTemperatureUnit());
            assertNull(
                    decoded.force12Hour());
            assertNull(
                    decoded.force24Hour());
            assertNull(
                    decoded.archivedInflection());
            assertTrue(
                    protobuf.length > archive.length);
            assertEquals(
                    PbBridgeCodec.TYPE_LANGUAGE_AND_LOCALE,
                    message.protobufType());
            assertFalse(
                    message.response());
        } finally {
            decoded.destroy();
            message.destroy();
            wipe(archive);
            wipe(protobuf);
        }
    }

    @Test
    public void decodesIndependentFoundationSecureArchive() {
        byte[] archive =
                Base64.getDecoder().decode(
                        FOUNDATION_FIXTURE);
        LocalePreferencesArchiveCodec.Preferences decoded =
                LocalePreferencesArchiveCodec.decode(
                        archive);
        try {
            assertEquals(
                    List.of(
                            "en-US",
                            "uk-UA"),
                    decoded.appleLanguages());
            assertEquals(
                    "uk_UA",
                    decoded.appleLocale());
        } finally {
            decoded.destroy();
            wipe(archive);
        }
    }

    @Test
    public void rejectsMissingUnsafeOrAmbiguousPreferences() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalePreferencesArchiveCodec.encodeSetupPreferences(
                        List.of(),
                        "uk_UA"));
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalePreferencesArchiveCodec.encodeSetupPreferences(
                        List.of(
                                "uk-UA",
                                "uk-UA"),
                        "uk_UA"));
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalePreferencesArchiveCodec.encodeSetupPreferences(
                        List.of(
                                "uk-UA"),
                        ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalePreferencesArchiveCodec.decode(
                        new byte[0]));

        LinkedHashMap<String, Object> root =
                new LinkedHashMap<>();
        root.put(
                "$version",
                100000L);
        root.put(
                "$archiver",
                "NSKeyedArchiver");
        root.put(
                "$top",
                new LinkedHashMap<>());
        root.put(
                "$objects",
                List.of(
                        "$null"));
        byte[] wrongTop =
                AppleBinaryPropertyList.encode(
                        root);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LocalePreferencesArchiveCodec.decode(
                            wrongTop));
        } finally {
            wipe(wrongTop);
        }
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}

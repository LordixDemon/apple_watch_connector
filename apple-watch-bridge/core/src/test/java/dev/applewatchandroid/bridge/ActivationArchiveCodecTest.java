package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public final class ActivationArchiveCodecTest {
    static final String FOUNDATION_REQUEST_ARCHIVE = """
            YnBsaXN0MDDUAQIDBAUGBwpYJHZlcnNpb25ZJGFyY2hpdmVyVCR0b3BYJG9iamVjdHMSAAGGoF8QD05TS2V5ZWRBcmNoaXZl
            ctEICV8QHFBCQnJpZGdlQWN0aXZhdGlvblJlcXVlc3RLZXmAAa8QGwsMeHl/gIaHiEZMXopui4yam5ydnp+goaKmp1UkbnVs
            bN8QOA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0BBQkNERUZHRUZFRkpLTE1O
            T09NS1NPVVZGWEZGR0ZGXl9PYU9FRmRGRmdPT0VHXkZsT25GRkZyRk91Rk9fEBhyZXF1aXJlc0ROU1NFQ1ZhbGlkYXRpb25f
            EBJpc1dlYlNlYXJjaENvbnRlbnRfEB9fX25zdXJscmVxdWVzdF9wcm90b19wcm9wX29ial85XxAUYWxsb3dlZFByb3RvY29s
            VHlwZXNdYmxvY2tUcmFja2Vyc18QHmFsbG93c0NvbnN0cmFpbmVkTmV0d29ya0FjY2Vzc18QE2Fzc3VtZXNIVFRQM0NhcGFi
            bGVfECBfX25zdXJscmVxdWVzdF9wcm90b19wcm9wX29ial8xMF8QIF9fbnN1cmxyZXF1ZXN0X3Byb3RvX3Byb3Bfb2JqXzEx
            W2F0dHJpYnV0aW9uXxAgX19uc3VybHJlcXVlc3RfcHJvdG9fcHJvcF9vYmpfMTJfECBfX25zdXJscmVxdWVzdF9wcm90b19w
            cm9wX29ial8yMF8QGl9fbnN1cmxyZXF1ZXN0X3Byb3RvX3Byb3BzXxAgX19uc3VybHJlcXVlc3RfcHJvdG9fcHJvcF9vYmpf
            MjFfECBfX25zdXJscmVxdWVzdF9wcm90b19wcm9wX29ial8xM18QIF9fbnN1cmxyZXF1ZXN0X3Byb3RvX3Byb3Bfb2JqXzE0
            XxAgX19uc3VybHJlcXVlc3RfcHJvdG9fcHJvcF9vYmpfMTVfECBfX25zdXJscmVxdWVzdF9wcm90b19wcm9wX29ial8xNl8Q
            IF9fbnN1cmxyZXF1ZXN0X3Byb3RvX3Byb3Bfb2JqXzE3XxAgX19uc3VybHJlcXVlc3RfcHJvdG9fcHJvcF9vYmpfMThfEBZ1
            c2VFbmhhbmNlZFByaXZhY3lNb2RlXxAgX19uc3VybHJlcXVlc3RfcHJvdG9fcHJvcF9vYmpfMTlfECVhbGxvd1ByaXZhdGVB
            Y2Nlc3NUb2tlbnNGb3JUaGlyZFBhcnR5XxATYWxsb3dzUGVyc2lzdGVudEROU18QH19fbnN1cmxyZXF1ZXN0X3Byb3RvX3By
            b3Bfb2JqXzBfEB5yZXF1aXJlc1Nob3J0Q29ubmVjdGlvblRpbWVvdXRfEBZwcml2YWN5UHJveHlGYWlsQ2xvc2VkXxAacGF5
            bG9hZFRyYW5zbWlzc2lvblRpbWVvdXRfEB9fX25zdXJscmVxdWVzdF9wcm90b19wcm9wX29ial8xXxAnY29udGVudERpc3Bv
            c2l0aW9uRW5jb2RpbmdGYWxsYmFja0FycmF5XxAfX19uc3VybHJlcXVlc3RfcHJvdG9fcHJvcF9vYmpfMl50cmFja2VyQ29u
            dGV4dFlhbGxvd3NVQ0FfEBRwcm9oaWJpdFByaXZhY3lQcm94eV8QH19fbnN1cmxyZXF1ZXN0X3Byb3RvX3Byb3Bfb2JqXzNf
            EDBwcml2YWN5UHJveHlGYWlsQ2xvc2VkRm9yVW5yZWFjaGFibGVOb25NYWluSG9zdHNfEClwcml2YWN5UHJveHlGYWlsQ2xv
            c2VkRm9yVW5yZWFjaGFibGVIb3N0c1YkY2xhc3NfEB9fX25zdXJscmVxdWVzdF9wcm90b19wcm9wX29ial80XxAYYm91bmRJ
            bnRlcmZhY2VJZGVudGlmaWVyXxAcYWxsb3dzRXhwZW5zaXZlTmV0d29ya0FjY2Vzc18QH19fbnN1cmxyZXF1ZXN0X3Byb3Rv
            X3Byb3Bfb2JqXzVfEBBzdGFydFRpbWVvdXRUaW1lXxAScHJldmVudEhTVFNTdG9yYWdlXxAfX19uc3VybHJlcXVlc3RfcHJv
            dG9fcHJvcF9vYmpfNl8QGWNvb2tpZVBhcnRpdGlvbklkZW50aWZpZXJSJDBfECJmYWlsSW5zZWN1cmVMb2FkV2l0aEhUVFBT
            RE5TUmVjb3JkXGtub3duVHJhY2tlclppZ25vcmVIU1RTUiQxXxAbYWxsb3dPbmx5UGFydGl0aW9uZWRDb29raWVzXxAfX19u
            c3VybHJlcXVlc3RfcHJvdG9fcHJvcF9vYmpfN1IkMl8QIXNjaGVtZVdhc1VwZ3JhZGVkRHVlVG9EeW5hbWljSFNUU18QH19f
            bnN1cmxyZXF1ZXN0X3Byb3RvX3Byb3Bfb2JqXzgQAAiAAggIgAmAChAAgAuAGYAAgACAC4AKgAyAAIANgA4IgA8ICIACCAgj
            AAAAAAAAAACAA4AAgAaAAAiABwgIgBqAAIAAgAIIgAiAABACCAgIEAkIgAAQFgiAAAnTejJ7T31+V05TLmJhc2VbTlMucmVs
            YXRpdmWAAIAFgARfEChodHRwczovL2V4YW1wbGUuaW52YWxpZC9kZXZpY2VBY3RpdmF0aW9u0oGCg4RaJGNsYXNzbmFtZVgk
            Y2xhc3Nlc1VOU1VSTKKDhVhOU09iamVjdCNATgAAAAAAABABEIQIE///////////VFBPU1TTjY4yj5SZV05TLmtleXNaTlMu
            b2JqZWN0c6SQkZKTgBCAEYASgBOklZaXmIAUgBWAFoAXgBhaVXNlci1BZ2VudFxDb250ZW50LVR5cGVeQ29udGVudC1MZW5n
            dGhYX19oaGFhX19fEBBBcmNoaXZlRml4dHVyZS8xXxAZYXBwbGljYXRpb24veC1hcHBsZS1wbGlzdFE0XxDQDQoNClluQnNh
            WE4wTUREVEFRSURCQVlJV2xWelpYSXRRV2RsYm5SY1EyOXVkR1Z1ZEMxVWVYQmxYa052Ym5SbGJuUXRUR1Z1WjNSb29RVmZF
            QkJCY21Ob2FYWmxSbWw0ZEhWeVpTOHhvUWRmRUJsaGNIQnNhV05oZEdsdmJpOTRMV0Z3Y0d4bExYQnNhWE4wb1FsUk5BZ1BH
            aWMyT0V0TmFXc0FBQUFBQUFBQkFRQUFBQUFBQUFBS0FBQUFBQUFBQUFBQUFBQUFBQUFBYlE9PdKBgqOkXxATTlNNdXRhYmxl
            RGljdGlvbmFyeaOjpYVcTlNEaWN0aW9uYXJ5RAECAwTSgYKoqV8QE05TTXV0YWJsZVVSTFJlcXVlc3SjqquFXxATTlNNdXRh
            YmxlVVJMUmVxdWVzdFxOU1VSTFJlcXVlc3QACAARABoAJAApADIANwBJAEwAawBtAIsAkQEEAR8BNAFWAW0BewGcAbIB1QH4
            AgQCJwJKAmcCigKtAtAC8wMWAzkDXAN1A5gDwAPWA/gEGQQyBE8EcQSbBL0EzATWBO0FDwVCBW4FdQWXBbIF0QXzBgYGGwY9
            BlkGXAaBBo4GmQacBroG3AbfBwMHJQcnBygHKgcrBywHLgcwBzIHNAc2BzgHOgc8Bz4HQAdCB0QHRgdHB0kHSgdLB00HTgdP
            B1gHWgdcB14HYAdhB2MHZAdlB2cHaQdrB20HbgdwB3IHdAd1B3YHdwd5B3oHfAd+B38HgQeCB4kHkQedB58HoQejB84H0wfe
            B+cH7QfwB/kIAggECAYIBwgQCBUIHAgkCC8INAg2CDgIOgg8CEEIQwhFCEcISQhLCFYIYwhyCHsIjgiqCKwJfwmECZoJngmr
            CbAJtQnLCc8J5QAAAAAAAAIBAAAAAAAAAKwAAAAAAAAAAAAAAAAAAAny
            """;

    // Real NSKeyedArchiver output for {"Content-Type": ..., "X-Fixture": "value"}
    // encoded with forKey:@"PBBridgeActivationRequestKey" — the top key the
    // watch actually decodes with (see ActivationArchiveCodec note).
    private static final String FOUNDATION_HEADERS_ARCHIVE =
            "YnBsaXN0MDDUAQIDBAUGBwpYJHZlcnNpb25ZJGFyY2hpdmVyVCR0b3BYJG9iamVjdHMSAAGGoF8Q"
                    + "D05TS2V5ZWRBcmNoaXZlctEICV8QHFBCQnJpZGdlQWN0aXZhdGlvblJlcXVlc3RLZXmAAacLDBcY"
                    + "GRobVSRudWxs0w0ODxATFldOUy5rZXlzWk5TLm9iamVjdHNWJGNsYXNzohESgAKAA6IUFYAEgAWA"
                    + "BlxDb250ZW50LVR5cGVZWC1GaXh0dXJlXxAZYXBwbGljYXRpb24veC1hcHBsZS1wbGlzdFV2YWx1"
                    + "ZdIcHR4fWiRjbGFzc25hbWVYJGNsYXNzZXNcTlNEaWN0aW9uYXJ5oh4gWE5TT2JqZWN0AAgAEQAa"
                    + "ACQAKQAyADcASQBMAGsAbQB1AHsAggCKAJUAnACfAKEAowCmAKgAqgCsALkAwwDfAOUA6gD1AP4B"
                    + "CwEOAAAAAAAAAgEAAAAAAAAAIQAAAAAAAAAAAAAAAAAAARc=";

    @Test
    public void decodesIndependentFoundationUrlRequestArchive() {
        ActivationArchiveCodec.ActivationHttpRequest request =
                ActivationArchiveCodec.decodeRequest(
                        Base64.getMimeDecoder().decode(
                                FOUNDATION_REQUEST_ARCHIVE));
        try {
            assertEquals(
                    URI.create(
                            "https://example.invalid/deviceActivation"),
                    request.url());
            assertEquals(
                    "POST",
                    request.method());
            assertEquals(
                    Map.of(
                            "User-Agent",
                            "ArchiveFixture/1",
                            "Content-Type",
                            "application/x-apple-plist",
                            "Content-Length",
                            "4"),
                    request.headers());
            assertArrayEquals(
                    new byte[] {
                            1,
                            2,
                            3,
                            4
                    },
                    request.body());
            assertEquals(
                    60.0,
                    request.timeoutSeconds(),
                    0.0);
            assertEquals(
                    60000,
                    request.timeoutMillis());
            assertEquals(
                    1,
                    request.cachePolicy());
        } finally {
            request.destroy();
        }
        assertThrows(
                IllegalStateException.class,
                request::body);
    }

    @Test
    public void decodesIndependentFoundationHeaderArchive() {
        assertEquals(
                Map.of(
                        "Content-Type",
                        "application/x-apple-plist",
                        "X-Fixture",
                        "value"),
                ActivationArchiveCodec.decodeResponseHeaders(
                        Base64.getDecoder().decode(
                                FOUNDATION_HEADERS_ARCHIVE)));
    }

    @Test
    public void encodedHeaderArchiveRoundTripsDeterministically() {
        LinkedHashMap<String, String> headers =
                new LinkedHashMap<>();
        headers.put(
                "X-Zeta",
                "last");
        headers.put(
                "Content-Type",
                "application/x-apple-plist");

        byte[] first =
                ActivationArchiveCodec.encodeResponseHeaders(
                        headers);
        byte[] second =
                ActivationArchiveCodec.encodeResponseHeaders(
                        headers);

        assertArrayEquals(
                first,
                second);
        assertEquals(
                headers,
                ActivationArchiveCodec.decodeResponseHeaders(
                        first));
    }

    @Test
    public void malformedOrWrongArchiveFailsClosed() {
        byte[] headers =
                Base64.getDecoder().decode(
                        FOUNDATION_HEADERS_ARCHIVE);
        assertThrows(
                IllegalArgumentException.class,
                () -> ActivationArchiveCodec.decodeRequest(
                        headers));

        byte[] truncated =
                new byte[headers.length - 1];
        System.arraycopy(
                headers,
                0,
                truncated,
                0,
                truncated.length);
        assertThrows(
                IllegalArgumentException.class,
                () -> ActivationArchiveCodec.decodeResponseHeaders(
                        truncated));
        assertThrows(
                IllegalArgumentException.class,
                () -> ActivationArchiveCodec.encodeResponseHeaders(
                        Map.of(
                                "Bad\r\nHeader",
                                "value")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ActivationArchiveCodec.encodeResponseHeaders(
                        Map.of(
                                "X-Test",
                                "bad\r\nvalue")));
    }
}

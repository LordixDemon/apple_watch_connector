package dev.applewatchandroid.bridge;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict NSKeyedArchiver bridge for PBBridge MobileActivation traffic.
 *
 * <p>The request layout is taken from CFNetwork on watchOS 26.2 and 26.6:
 * archive version 9, 22 URLRequest objects, with URL/method/headers/body at
 * indices 1/18/19/20. No Objective-C class named by an archive is ever
 * instantiated.</p>
 */
final class ActivationArchiveCodec {
    static final String REQUEST_KEY =
            "PBBridgeActivationRequestKey";
    // watchOS 26.6 PBBridgeSupport quirk: the constant
    // _PBBridgeActivationResponseHeadersKey resolves to the SAME CFString
    // "PBBridgeActivationRequestKey" (verified on both watchOS 26.6 and
    // iOS 26.6 binaries). PBBridgeGizmoController decodes response headers
    // with decodeObjectOfClasses:forKey:@"PBBridgeActivationRequestKey",
    // so the response archive MUST use the request key as its top key —
    // otherwise headers decode to nil and mobileactivationd rejects the
    // activation with Code=-1 "Invalid activation signature."
    static final String RESPONSE_HEADERS_KEY =
            "PBBridgeActivationRequestKey";

    private static final String ARCHIVER =
            "NSKeyedArchiver";
    private static final long KEYED_ARCHIVE_VERSION =
            100000L;
    private static final long URL_REQUEST_ENCODING_VERSION =
            2L;
    private static final long URL_REQUEST_ARCHIVE_VERSION =
            9L;
    private static final long URL_REQUEST_OBJECT_COUNT =
            22L;
    private static final int URL_INDEX =
            1;
    private static final int TIMEOUT_INDEX =
            2;
    private static final int CACHE_POLICY_INDEX =
            3;
    private static final int METHOD_INDEX =
            18;
    private static final int HEADERS_INDEX =
            19;
    private static final int BODY_INDEX =
            20;
    private static final String SHADOWED_HEADER_ARRAYS =
            "__hhaa__";
    private static final int MAX_HEADERS =
            256;
    private static final int MAX_HEADER_COMPONENT_LENGTH =
            64 * 1024;
    private static final int MAX_TOTAL_HEADER_LENGTH =
            256 * 1024;
    private static final double MAX_TIMEOUT_SECONDS =
            300.0;

    private ActivationArchiveCodec() {
    }

    static ActivationHttpRequest decodeRequest(
            byte[] encoded) {
        Archive archive =
                decodeArchive(
                        encoded,
                        REQUEST_KEY);
        Map<String, Object> request =
                requireObjectDictionary(
                        archive.objects,
                        archive.topReference,
                        "activation request");
        requireClass(
                archive.objects,
                request.get(
                        "$class"),
                Set.of(
                        "NSMutableURLRequest",
                        "NSURLRequest"),
                "NSURLRequest");
        long encodingVersion =
                getRequiredLong(
                        request.get(
                                "$0"),
                        "NSURLRequest encoding version");
        if (encodingVersion < 1L || encodingVersion > 10L) {
            throw new IllegalArgumentException(
                    "NSURLRequest encoding version is unsupported: " + encodingVersion);
        }
        long archiveVersion =
                getRequiredLong(
                        request.get(
                                "$1"),
                        "NSURLRequest archive version");
        if (archiveVersion < 7L || archiveVersion > 20L) {
            throw new IllegalArgumentException(
                    "NSURLRequest archive version is unsupported: " + archiveVersion);
        }
        long objectCount =
                getRequiredLong(
                        request.get(
                                "$2"),
                        "NSURLRequest object count");
        if (objectCount < 21L) {
            throw new IllegalArgumentException(
                    "NSURLRequest object count is too small: " + objectCount);
        }
        int[] requiredIndices = {
                URL_INDEX,
                TIMEOUT_INDEX,
                CACHE_POLICY_INDEX,
                METHOD_INDEX,
                HEADERS_INDEX,
                BODY_INDEX
        };
        for (int index : requiredIndices) {
            if (!request.containsKey(
                    objectKey(
                            index))) {
                throw new IllegalArgumentException(
                        "NSURLRequest archive object is absent: " + index);
            }
        }

        URI url =
                decodeUrl(
                        archive.objects,
                        request.get(
                                objectKey(
                                        URL_INDEX)));
        String method =
                requireStringObject(
                        archive.objects,
                        request.get(
                                objectKey(
                                        METHOD_INDEX)),
                        "HTTP method");
        if (!"POST".equals(
                method)) {
            throw new IllegalArgumentException(
                    "MobileActivation request method is not POST");
        }
        Map<String, String> headers =
                decodeStringDictionary(
                        archive.objects,
                        request.get(
                                objectKey(
                                        HEADERS_INDEX)),
                        Set.of(
                                "NSMutableDictionary",
                                "NSDictionary"));
        byte[] body =
                requireDataObject(
                        archive.objects,
                        request.get(
                                objectKey(
                                        BODY_INDEX)),
                        "HTTP body");
        try {
            if (body.length == 0) {
                throw new IllegalArgumentException(
                        "MobileActivation request body is empty");
            }
            validateContentLength(
                    headers,
                    body.length);

            double timeoutSeconds =
                    requireNumberObject(
                            archive.objects,
                            request.get(
                                    objectKey(
                                            TIMEOUT_INDEX)),
                            "request timeout");
            if (!Double.isFinite(
                    timeoutSeconds)
                    || timeoutSeconds <= 0.0
                    || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException(
                        "MobileActivation request timeout is invalid");
            }
            long cachePolicy =
                    requireIntegralObject(
                            archive.objects,
                            request.get(
                                    objectKey(
                                            CACHE_POLICY_INDEX)),
                            "request cache policy");
            if (cachePolicy < 0
                    || cachePolicy > 5) {
                throw new IllegalArgumentException(
                        "MobileActivation cache policy is invalid");
            }
            return new ActivationHttpRequest(
                    url,
                    method,
                    headers,
                    body,
                    timeoutSeconds,
                    (int) cachePolicy);
        } catch (RuntimeException failure) {
            Arrays.fill(
                    body,
                    (byte) 0);
            throw failure;
        }
    }

    static byte[] encodeResponseHeaders(
            Map<String, String> source) {
        List<Map.Entry<String, String>> headers =
                validatedSortedHeaders(
                        source,
                        false);
        int classIndex =
                2 + headers.size() * 2;
        List<Object> objects =
                new ArrayList<>(
                        classIndex + 1);
        objects.add(
                "$null");

        List<Object> keyReferences =
                new ArrayList<>(
                        headers.size());
        List<Object> valueReferences =
                new ArrayList<>(
                        headers.size());
        for (int index = 0;
                index < headers.size();
                index++) {
            keyReferences.add(
                    new AppleBinaryPropertyList.Uid(
                            2 + index));
            valueReferences.add(
                    new AppleBinaryPropertyList.Uid(
                            2 + headers.size() + index));
        }
        LinkedHashMap<String, Object> dictionary =
                new LinkedHashMap<>();
        dictionary.put(
                "NS.keys",
                keyReferences);
        dictionary.put(
                "NS.objects",
                valueReferences);
        dictionary.put(
                "$class",
                new AppleBinaryPropertyList.Uid(
                        classIndex));
        objects.add(
                dictionary);
        for (Map.Entry<String, String> header :
                headers) {
            objects.add(
                    header.getKey());
        }
        for (Map.Entry<String, String> header :
                headers) {
            objects.add(
                    header.getValue());
        }
        LinkedHashMap<String, Object> classDescriptor =
                new LinkedHashMap<>();
        classDescriptor.put(
                "$classname",
                "NSDictionary");
        classDescriptor.put(
                "$classes",
                List.of(
                        "NSDictionary",
                        "NSObject"));
        objects.add(
                classDescriptor);

        LinkedHashMap<String, Object> top =
                new LinkedHashMap<>();
        top.put(
                RESPONSE_HEADERS_KEY,
                new AppleBinaryPropertyList.Uid(
                        1));
        LinkedHashMap<String, Object> root =
                new LinkedHashMap<>();
        root.put(
                "$version",
                KEYED_ARCHIVE_VERSION);
        root.put(
                "$archiver",
                ARCHIVER);
        root.put(
                "$top",
                top);
        root.put(
                "$objects",
                objects);
        return AppleBinaryPropertyList.encode(
                root);
    }

    static Map<String, String> decodeResponseHeaders(
            byte[] encoded) {
        Archive archive =
                decodeArchive(
                        encoded,
                        RESPONSE_HEADERS_KEY);
        return decodeStringDictionary(
                archive.objects,
                archive.topReference,
                Set.of(
                        "NSDictionary",
                        "NSMutableDictionary"));
    }

    private static Archive decodeArchive(
            byte[] encoded,
            String topKey) {
        Map<String, Object> root =
                requireDictionary(
                        AppleBinaryPropertyList.decode(
                                encoded),
                        "keyed archive root");
        if (root.size() != 4
                || !ARCHIVER.equals(
                root.get(
                        "$archiver"))) {
            throw new IllegalArgumentException(
                    "NSKeyedArchiver root metadata is invalid");
        }
        requireLong(
                root.get(
                        "$version"),
                KEYED_ARCHIVE_VERSION,
                "NSKeyedArchiver version");
        List<Object> objects =
                requireList(
                        root.get(
                                "$objects"),
                        "NSKeyedArchiver objects");
        if (objects.size() < 2
                || !"$null".equals(
                objects.get(
                        0))) {
            throw new IllegalArgumentException(
                    "NSKeyedArchiver null object is invalid");
        }
        Map<String, Object> top =
                requireDictionary(
                        root.get(
                                "$top"),
                        "NSKeyedArchiver top object");
        if (top.size() != 1
                || !top.containsKey(
                topKey)) {
            throw new IllegalArgumentException(
                    "NSKeyedArchiver top key is invalid");
        }
        AppleBinaryPropertyList.Uid topReference =
                requireUid(
                        top.get(
                                topKey),
                        "NSKeyedArchiver top reference");
        requireObjectIndex(
                objects,
                topReference,
                "NSKeyedArchiver top reference");
        return new Archive(
                objects,
                topReference);
    }

    private static URI decodeUrl(
            List<Object> objects,
            Object reference) {
        Map<String, Object> encodedUrl =
                requireObjectDictionary(
                        objects,
                        reference,
                        "request URL");
        requireClass(
                objects,
                encodedUrl.get(
                        "$class"),
                Set.of(
                        "NSURL"),
                "NSURL");
        Object base =
                resolveObject(
                        objects,
                        encodedUrl.get(
                                "NS.base"),
                        "base URL");
        if (!"$null".equals(
                base)) {
            throw new IllegalArgumentException(
                    "MobileActivation request URL is not absolute");
        }
        String relative =
                requireStringObject(
                        objects,
                        encodedUrl.get(
                                "NS.relative"),
                        "request URL string");
        try {
            URI url =
                    new URI(
                            relative);
            if (!url.isAbsolute()
                    || url.getScheme() == null
                    || !"https".equalsIgnoreCase(
                    url.getScheme())
                    || url.getHost() == null
                    || url.getHost().isEmpty()
                    || url.getRawUserInfo() != null
                    || url.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        "MobileActivation request URL is not safe HTTPS");
            }
            return url;
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException(
                    "MobileActivation request URL is malformed",
                    malformed);
        }
    }

    private static Map<String, String> decodeStringDictionary(
            List<Object> objects,
            Object reference,
            Set<String> allowedClassNames) {
        Map<String, Object> dictionary =
                requireObjectDictionary(
                        objects,
                        reference,
                        "archived string dictionary");
        requireClass(
                objects,
                dictionary.get(
                        "$class"),
                allowedClassNames,
                "NSDictionary");
        List<Object> keys =
                requireList(
                        dictionary.get(
                                "NS.keys"),
                        "archived dictionary keys");
        List<Object> values =
                requireList(
                        dictionary.get(
                                "NS.objects"),
                        "archived dictionary values");
        if (keys.size() != values.size()
                || keys.size() > MAX_HEADERS) {
            throw new IllegalArgumentException(
                    "Archived header dictionary size is invalid");
        }
        LinkedHashMap<String, String> result =
                new LinkedHashMap<>();
        Set<String> caseFolded =
                new HashSet<>();
        int totalLength =
                0;
        for (int index = 0;
                index < keys.size();
                index++) {
            String key =
                    requireStringObject(
                            objects,
                            keys.get(
                                    index),
                            "header name");
            String value =
                    requireStringObject(
                            objects,
                            values.get(
                                    index),
                            "header value");
            if (SHADOWED_HEADER_ARRAYS.equals(
                    key)) {
                validateShadowedHeaderMetadata(
                        value);
                continue;
            }
            validateHeaderComponent(
                    key,
                    true,
                    "header name");
            validateHeaderComponent(
                    value,
                    false,
                    "header value");
            String folded =
                    key.toLowerCase(
                            Locale.ROOT);
            if (!caseFolded.add(
                    folded)) {
                throw new IllegalArgumentException(
                        "Archived headers contain a duplicate name");
            }
            totalLength =
                    Math.addExact(
                            totalLength,
                            Math.addExact(
                                    key.length(),
                                    value.length()));
            if (totalLength > MAX_TOTAL_HEADER_LENGTH) {
                throw new IllegalArgumentException(
                        "Archived headers are too large");
            }
            result.put(
                    key,
                    value);
        }
        return Collections.unmodifiableMap(
                result);
    }

    private static List<Map.Entry<String, String>> validatedSortedHeaders(
            Map<String, String> source,
            boolean allowShadowMetadata) {
        if (source == null
                || source.size() > MAX_HEADERS) {
            throw new IllegalArgumentException(
                    "Response headers are invalid");
        }
        List<Map.Entry<String, String>> result =
                new ArrayList<>(
                        source.size());
        Set<String> caseFolded =
                new HashSet<>();
        int totalLength =
                0;
        for (Map.Entry<String, String> header :
                source.entrySet()) {
            String key =
                    header.getKey();
            String value =
                    header.getValue();
            validateHeaderComponent(
                    key,
                    true,
                    "response header name");
            validateHeaderComponent(
                    value,
                    false,
                    "response header value");
            if (!allowShadowMetadata
                    && SHADOWED_HEADER_ARRAYS.equals(
                    key)) {
                throw new IllegalArgumentException(
                        "Reserved CFNetwork header metadata is forbidden");
            }
            String folded =
                    key.toLowerCase(
                            Locale.ROOT);
            if (!caseFolded.add(
                    folded)) {
                throw new IllegalArgumentException(
                        "Response headers contain a duplicate name");
            }
            totalLength =
                    Math.addExact(
                            totalLength,
                            Math.addExact(
                                    key.length(),
                                    value.length()));
            if (totalLength > MAX_TOTAL_HEADER_LENGTH) {
                throw new IllegalArgumentException(
                        "Response headers are too large");
            }
            result.add(
                    Map.entry(
                            key,
                            value));
        }
        result.sort(
                Comparator.comparing(
                                Map.Entry<String, String>::getKey,
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(
                                Map.Entry::getKey));
        return result;
    }

    private static void validateShadowedHeaderMetadata(
            String value) {
        if (value == null
                || value.isEmpty()
                || value.length() > MAX_HEADER_COMPONENT_LENGTH
                || value.indexOf(
                0) >= 0) {
            throw new IllegalArgumentException(
                    "Shadowed CFNetwork header metadata is invalid");
        }
    }

    private static void validateHeaderComponent(
            String value,
            boolean name,
            String label) {
        if (value == null
                || value.isEmpty()
                || value.length() > MAX_HEADER_COMPONENT_LENGTH) {
            throw new IllegalArgumentException(
                    label + " is invalid");
        }
        for (int index = 0;
                index < value.length();
                index++) {
            char character =
                    value.charAt(
                            index);
            if (name) {
                if (!isHeaderTokenCharacter(
                        character)) {
                    throw new IllegalArgumentException(
                            label + " contains a forbidden character");
                }
            } else if (character == '\r'
                    || character == '\n'
                    || character == 0
                    || (character < 0x20
                    && character != '\t')) {
                throw new IllegalArgumentException(
                        label + " contains a forbidden character");
            }
        }
    }

    private static boolean isHeaderTokenCharacter(
            char character) {
        if (character >= '0'
                && character <= '9'
                || character >= 'A'
                && character <= 'Z'
                || character >= 'a'
                && character <= 'z') {
            return true;
        }
        return switch (character) {
            case '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^',
                    '_', '`', '|', '~' -> true;
            default -> false;
        };
    }

    private static void validateContentLength(
            Map<String, String> headers,
            int bodyLength) {
        String encodedLength =
                null;
        for (Map.Entry<String, String> header :
                headers.entrySet()) {
            if ("Content-Length".equalsIgnoreCase(
                    header.getKey())) {
                encodedLength =
                        header.getValue();
                break;
            }
        }
        if (encodedLength == null) {
            return;
        }
        try {
            if (Long.parseLong(
                    encodedLength) != bodyLength) {
                throw new IllegalArgumentException(
                        "MobileActivation Content-Length does not match body");
            }
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    "MobileActivation Content-Length is malformed",
                    malformed);
        }
    }

    private static void requireClass(
            List<Object> objects,
            Object reference,
            Set<String> allowedClassNames,
            String requiredBaseClass) {
        Map<String, Object> descriptor =
                requireObjectDictionary(
                        objects,
                        reference,
                        "class descriptor");
        if (descriptor.size() != 2
                || !(descriptor.get(
                "$classname") instanceof String className)
                || !allowedClassNames.contains(
                className)) {
            throw new IllegalArgumentException(
                    "NSKeyedArchiver class descriptor is invalid");
        }
        List<Object> classes =
                requireList(
                        descriptor.get(
                                "$classes"),
                        "class hierarchy");
        if (!classes.contains(
                requiredBaseClass)
                || !classes.contains(
                "NSObject")) {
            throw new IllegalArgumentException(
                    "NSKeyedArchiver class hierarchy is invalid");
        }
        for (Object item :
                classes) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException(
                        "NSKeyedArchiver class hierarchy is malformed");
            }
        }
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

    private static String requireStringObject(
            List<Object> objects,
            Object reference,
            String label) {
        Object value =
                resolveObject(
                        objects,
                        reference,
                        label);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(
                    label + " is not a string");
        }
        return stringValue;
    }

    private static byte[] requireDataObject(
            List<Object> objects,
            Object reference,
            String label) {
        Object value =
                resolveObject(
                        objects,
                        reference,
                        label);
        if (!(value instanceof byte[] data)) {
            throw new IllegalArgumentException(
                    label + " is not data");
        }
        return data.clone();
    }

    private static double requireNumberObject(
            List<Object> objects,
            Object reference,
            String label) {
        Object value =
                resolveObject(
                        objects,
                        reference,
                        label);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    label + " is not numeric");
        }
        return number.doubleValue();
    }

    private static long requireIntegralObject(
            List<Object> objects,
            Object reference,
            String label) {
        Object value =
                resolveObject(
                        objects,
                        reference,
                        label);
        if (!(value instanceof Long longValue)) {
            throw new IllegalArgumentException(
                    label + " is not an integer");
        }
        return longValue;
    }

    private static Object resolveObject(
            List<Object> objects,
            Object reference,
            String label) {
        AppleBinaryPropertyList.Uid uid =
                requireUid(
                        reference,
                        label);
        int index =
                requireObjectIndex(
                        objects,
                        uid,
                        label);
        return objects.get(
                index);
    }

    private static int requireObjectIndex(
            List<Object> objects,
            AppleBinaryPropertyList.Uid uid,
            String label) {
        int index =
                uid.value();
        if (index < 0
                || index >= objects.size()) {
            throw new IllegalArgumentException(
                    label + " points outside the object table");
        }
        return index;
    }

    private static AppleBinaryPropertyList.Uid requireUid(
            Object value,
            String label) {
        if (!(value instanceof AppleBinaryPropertyList.Uid uid)) {
            throw new IllegalArgumentException(
                    label + " is not a UID reference");
        }
        return uid;
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

    private static long getRequiredLong(
            Object value,
            String label) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException(
                label + " is invalid");
    }

    private static void requireLong(
            Object value,
            long expected,
            String label) {
        if (!(value instanceof Long longValue)
                || longValue != expected) {
            throw new IllegalArgumentException(
                    label + " is invalid");
        }
    }

    private static String objectKey(
            int index) {
        return "__nsurlrequest_proto_prop_obj_"
                + index;
    }

    static final class ActivationHttpRequest {
        private final URI url;
        private final String method;
        private final Map<String, String> headers;
        private byte[] body;
        private final double timeoutSeconds;
        private final int cachePolicy;

        private ActivationHttpRequest(
                URI url,
                String method,
                Map<String, String> headers,
                byte[] body,
                double timeoutSeconds,
                int cachePolicy) {
            this.url = url;
            this.method = method;
            this.headers = headers;
            this.body = body;
            this.timeoutSeconds = timeoutSeconds;
            this.cachePolicy = cachePolicy;
        }

        URI url() {
            requireValid();
            return url;
        }

        String method() {
            requireValid();
            return method;
        }

        Map<String, String> headers() {
            requireValid();
            return headers;
        }

        byte[] body() {
            requireValid();
            return body.clone();
        }

        double timeoutSeconds() {
            requireValid();
            return timeoutSeconds;
        }

        int timeoutMillis() {
            requireValid();
            return Math.toIntExact(
                    Math.round(
                            timeoutSeconds * 1000.0));
        }

        int cachePolicy() {
            requireValid();
            return cachePolicy;
        }

        void destroy() {
            if (body != null) {
                Arrays.fill(
                        body,
                        (byte) 0);
                body =
                        null;
            }
        }

        private void requireValid() {
            if (body == null) {
                throw new IllegalStateException(
                        "Activation request was destroyed");
            }
        }
    }

    private static final class Archive {
        private final List<Object> objects;
        private final AppleBinaryPropertyList.Uid topReference;

        private Archive(
                List<Object> objects,
                AppleBinaryPropertyList.Uid topReference) {
            this.objects = objects;
            this.topReference = topReference;
        }
    }
}

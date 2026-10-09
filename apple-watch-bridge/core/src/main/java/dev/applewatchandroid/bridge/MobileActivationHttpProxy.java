package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production-TLS HTTP proxy for Watch MobileActivation requests.
 *
 * <p>The proxy never creates an activation ticket and never changes the
 * Watch-selected endpoint. It sends the decoded request through a shared
 * OkHttp client that negotiates HTTP/2 via ALPN and reuses one TLS
 * connection for both activation legs, mirroring the iPhone relay, and
 * returns the server body plus a secure header archive for PBBridge
 * type 2.</p>
 */
final class MobileActivationHttpProxy {
    static final String ACTIVATION_MEDIUM_HEADER =
            "X-iOS-Activation-Medium";
    static final String ACTIVATION_MEDIUM_VALUE =
            "WiFi";

    private static final int MAX_REDIRECTS =
            20;
    private static final int MAX_RESPONSE_LENGTH =
            1024 * 1024;
    private static final int MAX_NETWORK_RETRIES =
            1;

    enum RequestKind {
        SESSION,
        ACTIVATION
    }

    interface Exchange {
        HttpResponse execute(
                HttpRequest request)
                throws IOException;
    }

    private final Exchange exchange;

    MobileActivationHttpProxy() {
        this(
                new SystemHttpsExchange());
    }

    MobileActivationHttpProxy(
            Exchange exchange) {
        if (exchange == null) {
            throw new IllegalArgumentException(
                    "Activation HTTP exchange is absent");
        }
        this.exchange = exchange;
    }

    /** Exchanges that want to know which activation leg they serve. */
    interface KindAwareExchange
            extends Exchange {
        void setRequestKind(
                RequestKind kind);
    }

    /** Diagnostics hook for the exact wire view of both activation legs. */
    interface WireTap {
        void onWireRequest(
                RequestKind kind,
                String method,
                String url,
                List<String> orderedHeaders,
                byte[] body);

        void onWireResponse(
                RequestKind kind,
                int statusCode,
                String protocol,
                List<String> orderedHeaders);
    }

    static volatile WireTap wireTap;

    ProxyResponse execute(
            RequestKind kind,
            byte[] archivedRequest)
            throws IOException {
        if (kind == null) {
            throw new IllegalArgumentException(
                    "Activation request kind is absent");
        }
        if (exchange
                instanceof KindAwareExchange kindAware) {
            kindAware.setRequestKind(
                    kind);
        }
        ActivationArchiveCodec.ActivationHttpRequest decoded =
                ActivationArchiveCodec.decodeRequest(
                        archivedRequest);
        HttpRequest current =
                null;
        byte[] initialBody =
                null;
        try {
            LinkedHashMap<String, String> initialHeaders =
                    new LinkedHashMap<>(
                            decoded.headers());
            putReplacingCaseInsensitive(
                    initialHeaders,
                    ACTIVATION_MEDIUM_HEADER,
                    ACTIVATION_MEDIUM_VALUE);
            initialBody =
                    decoded.body();
            current =
                    new HttpRequest(
                            decoded.url(),
                            decoded.method(),
                            initialHeaders,
                            initialBody,
                            decoded.timeoutMillis(),
                            decoded.cachePolicy());
        } finally {
            wipe(
                    initialBody);
            decoded.destroy();
        }

        int redirects =
                0;
        int retries =
                0;
        try {
            while (true) {
                HttpResponse response;
                try {
                    response =
                            exchange.execute(
                                    current);
                    retries =
                            0;
                } catch (IOException networkFailure) {
                    if (retries >= MAX_NETWORK_RETRIES) {
                        throw new IOException(
                                "MobileActivation HTTPS request failed",
                                networkFailure);
                    }
                    retries++;
                    continue;
                }
                if (response == null) {
                    throw new IOException(
                            "MobileActivation HTTPS response is absent");
                }
                try {
                    String location =
                            findHeader(
                                    response.headers,
                                    "Location");
                    if (location != null
                            && isRedirectStatus(
                            response.statusCode)) {
                        if (redirects >= MAX_REDIRECTS) {
                            throw new IOException(
                                    "MobileActivation redirect limit exceeded");
                        }
                        HttpRequest redirected =
                                createRedirectRequest(
                                        current,
                                        response.statusCode,
                                        location);
                        current.destroy();
                        current =
                                redirected;
                        redirects++;
                        continue;
                    }
                    if (response.statusCode >= 400
                            && kind == RequestKind.ACTIVATION) {
                        throw new ActivationRejectedException(
                                response.statusCode);
                    }
                    if (response.body.length == 0) {
                        throw new IOException(
                                "MobileActivation HTTPS response body is empty");
                    }
                    byte[] archivedHeaders =
                            ActivationArchiveCodec.encodeResponseHeaders(
                                    response.headers);
                    return new ProxyResponse(
                            response.statusCode,
                            response.body,
                            archivedHeaders);
                } finally {
                    response.destroy();
                }
            }
        } finally {
            current.destroy();
        }
    }

    private static HttpRequest createRedirectRequest(
            HttpRequest previous,
            int statusCode,
            String location)
            throws IOException {
        URI redirectUrl =
                resolveRedirectUrl(
                        previous.url,
                        location);
        LinkedHashMap<String, String> proposedHeaders =
                new LinkedHashMap<>(
                        previous.headers);
        removeCaseInsensitive(
                proposedHeaders,
                "Host");
        if (!sameOrigin(
                previous.url,
                redirectUrl)) {
            removeCaseInsensitive(
                    proposedHeaders,
                    "Authorization");
            removeCaseInsensitive(
                    proposedHeaders,
                    "Proxy-Authorization");
            removeCaseInsensitive(
                    proposedHeaders,
                    "Cookie");
        }

        String proposedMethod =
                previous.method;
        byte[] proposedBody =
                previous.body == null
                        ? null
                        : previous.body.clone();
        if ((statusCode == HttpURLConnection.HTTP_MOVED_PERM
                || statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                || statusCode == HttpURLConnection.HTTP_SEE_OTHER)
                && !"GET".equals(
                previous.method)
                && !"HEAD".equals(
                previous.method)) {
            proposedMethod =
                    "GET";
            wipe(
                    proposedBody);
            proposedBody =
                    null;
            removeCaseInsensitive(
                    proposedHeaders,
                    "Content-Type");
            removeCaseInsensitive(
                    proposedHeaders,
                    "Content-Length");
            removeCaseInsensitive(
                    proposedHeaders,
                    "Transfer-Encoding");
        }

        if (statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                && previous.body != null) {
            proposedMethod =
                    "POST";
            copyHeaderOrRemove(
                    previous.headers,
                    proposedHeaders,
                    "Content-Type");
            copyHeaderOrRemove(
                    previous.headers,
                    proposedHeaders,
                    "Content-Length");
            wipe(
                    proposedBody);
            proposedBody =
                    previous.body.clone();
        }
        try {
            return new HttpRequest(
                    redirectUrl,
                    proposedMethod,
                    proposedHeaders,
                    proposedBody,
                    previous.timeoutMillis,
                    previous.cachePolicy);
        } finally {
            wipe(
                    proposedBody);
        }
    }

    private static URI resolveRedirectUrl(
            URI previous,
            String location)
            throws IOException {
        try {
            URI resolved =
                    previous.resolve(
                            new URI(
                                    location));
            if (!resolved.isAbsolute()
                    || resolved.getScheme() == null
                    || !"https".equalsIgnoreCase(
                    resolved.getScheme())
                    || resolved.getHost() == null
                    || resolved.getHost().isEmpty()
                    || resolved.getRawUserInfo() != null
                    || resolved.getRawFragment() != null) {
                throw new IOException(
                        "MobileActivation redirect is not safe HTTPS");
            }
            return resolved;
        } catch (IllegalArgumentException
                | URISyntaxException malformed) {
            throw new IOException(
                    "MobileActivation redirect URL is malformed",
                    malformed);
        }
    }

    private static boolean sameOrigin(
            URI first,
            URI second) {
        return first.getScheme().equalsIgnoreCase(
                second.getScheme())
                && first.getHost().equalsIgnoreCase(
                second.getHost())
                && effectivePort(
                first) == effectivePort(
                second);
    }

    private static int effectivePort(
            URI uri) {
        return uri.getPort() >= 0
                ? uri.getPort()
                : 443;
    }

    private static boolean isRedirectStatus(
            int statusCode) {
        return statusCode == HttpURLConnection.HTTP_MOVED_PERM
                || statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                || statusCode == HttpURLConnection.HTTP_SEE_OTHER
                || statusCode == 307
                || statusCode == 308;
    }

    private static void copyHeaderOrRemove(
            Map<String, String> source,
            LinkedHashMap<String, String> destination,
            String name) {
        String value =
                findHeader(
                        source,
                        name);
        removeCaseInsensitive(
                destination,
                name);
        if (value != null) {
            destination.put(
                    name,
                    value);
        }
    }

    private static void putReplacingCaseInsensitive(
            LinkedHashMap<String, String> headers,
            String name,
            String value) {
        removeCaseInsensitive(
                headers,
                name);
        headers.put(
                name,
                value);
    }

    private static void removeCaseInsensitive(
            LinkedHashMap<String, String> headers,
            String name) {
        String matched =
                null;
        for (String existing :
                headers.keySet()) {
            if (name.equalsIgnoreCase(
                    existing)) {
                matched =
                        existing;
                break;
            }
        }
        if (matched != null) {
            headers.remove(
                    matched);
        }
    }

    private static String findHeader(
            Map<String, String> headers,
            String name) {
        for (Map.Entry<String, String> header :
                headers.entrySet()) {
            if (name.equalsIgnoreCase(
                    header.getKey())) {
                return header.getValue();
            }
        }
        return null;
    }

    static final class HttpRequest {
        private final URI url;
        private final String method;
        private final Map<String, String> headers;
        private byte[] body;
        private final int timeoutMillis;
        private final int cachePolicy;

        private HttpRequest(
                URI url,
                String method,
                Map<String, String> headers,
                byte[] body,
                int timeoutMillis,
                int cachePolicy) {
            if (url == null
                    || method == null
                    || headers == null
                    || timeoutMillis <= 0) {
                throw new IllegalArgumentException(
                        "Activation HTTP request is invalid");
            }
            this.url = url;
            this.method = method;
            this.headers =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    headers));
            this.body =
                    body == null
                            ? null
                            : body.clone();
            this.timeoutMillis = timeoutMillis;
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
            return body == null
                    ? null
                    : body.clone();
        }

        int timeoutMillis() {
            requireValid();
            return timeoutMillis;
        }

        int cachePolicy() {
            requireValid();
            return cachePolicy;
        }

        private void destroy() {
            wipe(
                    body);
            body =
                    null;
        }

        private void requireValid() {
            if (body == null
                    && !"GET".equals(
                    method)
                    && !"HEAD".equals(
                    method)) {
                throw new IllegalStateException(
                        "Activation HTTP request was destroyed");
            }
        }
    }

    static final class HttpResponse {
        private final int statusCode;
        private final Map<String, String> headers;
        private byte[] body;

        HttpResponse(
                int statusCode,
                Map<String, String> headers,
                byte[] body) {
            if (statusCode < 100
                    || statusCode > 599
                    || headers == null
                    || body == null
                    || body.length > MAX_RESPONSE_LENGTH) {
                throw new IllegalArgumentException(
                        "Activation HTTP response is invalid");
            }
            this.statusCode = statusCode;
            this.headers =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    headers));
            this.body = body.clone();
        }

        int statusCode() {
            return statusCode;
        }

        Map<String, String> headers() {
            return headers;
        }

        byte[] body() {
            return body.clone();
        }

        private void destroy() {
            wipe(
                    body);
            body =
                    null;
        }
    }

    static final class ProxyResponse {
        private final int statusCode;
        private byte[] body;
        private byte[] archivedHeaders;

        private ProxyResponse(
                int statusCode,
                byte[] body,
                byte[] archivedHeaders) {
            this.statusCode = statusCode;
            this.body = body.clone();
            this.archivedHeaders = archivedHeaders.clone();
        }

        int statusCode() {
            requireValid();
            return statusCode;
        }

        byte[] body() {
            requireValid();
            return body.clone();
        }

        byte[] archivedHeaders() {
            requireValid();
            return archivedHeaders.clone();
        }

        PbBridgeCodec.ActivationData toPbBridgeMessage() {
            requireValid();
            return new PbBridgeCodec.ActivationData(
                    body,
                    archivedHeaders);
        }

        void destroy() {
            wipe(
                    body);
            body =
                    null;
            wipe(
                    archivedHeaders);
            archivedHeaders =
                    null;
        }

        private void requireValid() {
            if (body == null
                    || archivedHeaders == null) {
                throw new IllegalStateException(
                        "Activation proxy response was destroyed");
            }
        }
    }

    static final class ActivationRejectedException
            extends IOException {
        private final int statusCode;

        private ActivationRejectedException(
                int statusCode) {
            super(
                    "MobileActivation server rejected activation");
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }

    /**
     * Posts an Activation Lock challenge answer (owner credentials) to the
     * page's submit URL, following redirects. Never logs the form body.
     * The caller must wipe {@code formBody} after the call returns.
     */
    static ProxyResponse executeChallengeSubmit(
            URI url,
            byte[] formBody)
            throws IOException {
        if (url == null
                || formBody == null
                || formBody.length == 0) {
            throw new IllegalArgumentException(
                    "Activation challenge submission is incomplete");
        }
        if (!"https".equalsIgnoreCase(
                url.getScheme())) {
            throw new IOException(
                    "Activation challenge submit is not HTTPS");
        }
        Exchange exchange =
                new SystemHttpsExchange();
        LinkedHashMap<String, String> headers =
                new LinkedHashMap<>();
        headers.put(
                "Content-Type",
                "application/x-www-form-urlencoded");
        headers.put(
                "User-Agent",
                "iOS 26.2 23S303 Watch Setup Assistant iOS Device "
                        + "Activator (MobileActivation-1068.64.1)");
        headers.put(
                "Accept",
                "*/*");
        headers.put(
                "Accept-Language",
                "en-US,en;q=0.9");
        headers.put(
                ACTIVATION_MEDIUM_HEADER,
                ACTIVATION_MEDIUM_VALUE);
        HttpRequest current =
                new HttpRequest(
                        url,
                        "POST",
                        headers,
                        formBody,
                        30_000,
                        0);
        int redirects =
                0;
        try {
            while (true) {
                HttpResponse response =
                        exchange.execute(
                                current);
                if (response == null) {
                    throw new IOException(
                            "Activation challenge response is absent");
                }
                try {
                    String location =
                            findHeader(
                                    response.headers,
                                    "Location");
                    if (location != null
                            && isRedirectStatus(
                            response.statusCode)) {
                        if (redirects >= MAX_REDIRECTS) {
                            throw new IOException(
                                    "Activation challenge redirect limit exceeded");
                        }
                        HttpRequest redirected =
                                createRedirectRequest(
                                        current,
                                        response.statusCode,
                                        location);
                        current.destroy();
                        current =
                                redirected;
                        redirects++;
                        continue;
                    }
                    byte[] archivedHeaders =
                            ActivationArchiveCodec.encodeResponseHeaders(
                                    response.headers);
                    return new ProxyResponse(
                            response.statusCode,
                            response.body,
                            archivedHeaders);
                } finally {
                    response.destroy();
                }
            }
        } finally {
            current.destroy();
        }
    }

    /** Package-visible factory for challenge-resolved activation records. */
    static ProxyResponse proxyResponseOf(
            int statusCode,
            byte[] body,
            byte[] archivedHeaders) {
        return new ProxyResponse(
                statusCode,
                body,
                archivedHeaders);
    }

    private static final class SystemHttpsExchange
            implements KindAwareExchange {
        private static volatile okhttp3.OkHttpClient sharedClient;

        private RequestKind kind =
                RequestKind.ACTIVATION;

        private static okhttp3.OkHttpClient sharedClient() {
            okhttp3.OkHttpClient client =
                    sharedClient;
            if (client == null) {
                synchronized (SystemHttpsExchange.class) {
                    client =
                            sharedClient;
                    if (client == null) {
                        client =
                                new okhttp3.OkHttpClient.Builder()
                                        .protocols(
                                        Arrays.asList(
                                                okhttp3.Protocol.HTTP_2,
                                                okhttp3.Protocol.HTTP_1_1))
                                        .followRedirects(
                                        false)
                                        .followSslRedirects(
                                        false)
                                        .retryOnConnectionFailure(
                                        true)
                                        .connectTimeout(
                                        30,
                                        java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(
                                        60,
                                        java.util.concurrent.TimeUnit.SECONDS)
                                        .writeTimeout(
                                        60,
                                        java.util.concurrent.TimeUnit.SECONDS)
                                        .build();
                        sharedClient =
                                client;
                    }
                }
            }
            return client;
        }

        @Override
        public void setRequestKind(
                RequestKind requestKind) {
            if (requestKind != null) {
                this.kind =
                        requestKind;
            }
        }

        @Override
        public HttpResponse execute(
                HttpRequest request)
                throws IOException {
            okhttp3.RequestBody requestBody =
                    request.body == null
                            ? null
                            : okhttp3.RequestBody.create(
                            request.body,
                            (okhttp3.MediaType) null);
            okhttp3.Request.Builder builder =
                    new okhttp3.Request.Builder()
                            .url(
                            request.url.toString())
                            .method(
                            request.method,
                            requestBody);
            List<String> orderedHeaders =
                    new ArrayList<>();
            for (Map.Entry<String, String> header :
                    request.headers.entrySet()) {
                if (!"Content-Length".equalsIgnoreCase(
                        header.getKey())
                        && !"Host".equalsIgnoreCase(
                        header.getKey())) {
                    builder.addHeader(
                            header.getKey(),
                            header.getValue());
                    orderedHeaders.add(
                            header.getKey()
                                    + ": "
                                    + header.getValue());
                }
            }
            WireTap tap =
                    wireTap;
            if (tap != null) {
                try {
                    tap.onWireRequest(
                            kind,
                            request.method,
                            request.url.toString(),
                            orderedHeaders,
                            request.body == null
                                    ? null
                                    : request.body.clone());
                } catch (RuntimeException tapFailure) {
                    // Diagnostics must never break activation.
                }
            }
            okhttp3.OkHttpClient callClient =
                    sharedClient()
                            .newBuilder()
                            .callTimeout(
                            request.timeoutMillis,
                            java.util.concurrent.TimeUnit.MILLISECONDS)
                            .build();
            try (okhttp3.Response response =
                         callClient
                                 .newCall(
                                 builder.build())
                                 .execute()) {
                int statusCode =
                        response.code();
                LinkedHashMap<String, String> headers =
                        new LinkedHashMap<>();
                List<String> orderedResponseHeaders =
                        new ArrayList<>();
                okhttp3.Headers responseHeaders =
                        response.headers();
                for (int index = 0;
                     index < responseHeaders.size();
                     index++) {
                    String name =
                            responseHeaders.name(
                                    index);
                    String value =
                            responseHeaders.value(
                                    index);
                    if (name.toLowerCase(
                            java.util.Locale.ROOT)
                            .startsWith(
                            "x-android-")) {
                        continue;
                    }
                    orderedResponseHeaders.add(
                            name
                                    + ": "
                                    + value);
                    String existing =
                            null;
                    for (String candidate :
                            headers.keySet()) {
                        if (name.equalsIgnoreCase(
                                candidate)) {
                            existing =
                                    candidate;
                            break;
                        }
                    }
                    if (existing == null) {
                        headers.put(
                                name,
                                value);
                    } else {
                        headers.put(
                                existing,
                                headers.get(
                                        existing)
                                        + ", "
                                        + value);
                    }
                }
                if (tap != null) {
                    try {
                        tap.onWireResponse(
                                kind,
                                statusCode,
                                response.protocol()
                                        .toString(),
                                orderedResponseHeaders);
                    } catch (RuntimeException tapFailure) {
                        // Diagnostics must never break activation.
                    }
                }
                okhttp3.ResponseBody responseBody =
                        response.body();
                long declaredLength =
                        responseBody == null
                                ? -1L
                                : responseBody.contentLength();
                if (declaredLength
                        > MAX_RESPONSE_LENGTH) {
                    throw new IOException(
                            "MobileActivation response is too large");
                }
                byte[] body =
                        responseBody == null
                                ? new byte[0]
                                : readBounded(
                                responseBody.byteStream());
                return new HttpResponse(
                        statusCode,
                        headers,
                        body);
            }
        }

        private static byte[] readBounded(
                InputStream stream)
                throws IOException {
            if (stream == null) {
                return new byte[0];
            }
            try (InputStream input =
                         stream) {
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream();
                byte[] buffer =
                        new byte[8192];
                while (true) {
                    int count =
                            input.read(
                                    buffer);
                    if (count < 0) {
                        break;
                    }
                    if (output.size() + count
                            > MAX_RESPONSE_LENGTH) {
                        throw new IOException(
                                "MobileActivation response is too large");
                    }
                    output.write(
                            buffer,
                            0,
                            count);
                }
                return output.toByteArray();
            }
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

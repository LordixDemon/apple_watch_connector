package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class MobileActivationHttpProxyTest {
    @Test
    public void asyncProxyLeavesCallerFreeUntilHttpCompletes() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var proxy = new MobileActivationHttpProxy(request -> {
            entered.countDown();
            try {
                if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test release timeout");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("cancelled", error);
            }
            return new MobileActivationHttpProxy.HttpResponse(200, Map.of(), new byte[] {5, 6});
        });
        try (var worker = new ActivationProxyWorker(proxy)) {
            byte[] request = foundationRequest();
            worker.start(7, 2, MobileActivationHttpProxy.RequestKind.SESSION, request);
            java.util.Arrays.fill(request, (byte) 0);
            org.junit.Assert.assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.Assert.assertTrue(worker.busy());
            assertNull(worker.poll());
            assertThrows(IllegalStateException.class, () -> worker.start(7, 3,
                    MobileActivationHttpProxy.RequestKind.SESSION, foundationRequest()));
            release.countDown();
            ActivationProxyWorker.Result result = null;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (result == null && System.nanoTime() < deadline) {
                result = worker.poll();
                if (result == null) Thread.sleep(1);
            }
            org.junit.Assert.assertNotNull(result);
            try (var completed = result) {
                assertEquals(7, completed.generation);
                assertEquals(2, completed.attempt);
                assertNull(completed.failure);
                var response = completed.takeResponse();
                try { assertArrayEquals(new byte[] {5, 6}, response.body()); }
                finally { response.destroy(); }
            }
            assertFalse(worker.busy());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void closingActivationWorkerCancelsItsSessionAndCannotPublishLateResult() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        var proxy = new MobileActivationHttpProxy(request -> {
            entered.countDown();
            try {
                new java.util.concurrent.CountDownLatch(1).await();
                throw new IOException("unexpected release");
            } catch (InterruptedException error) {
                throw new IOException("cancelled", error);
            } finally { exited.countDown(); }
        });
        var worker = new ActivationProxyWorker(proxy);
        try {
            worker.start(1, 1, MobileActivationHttpProxy.RequestKind.SESSION, foundationRequest());
            org.junit.Assert.assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally { worker.close(); }
        org.junit.Assert.assertTrue(exited.await(1, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(worker.busy());
        assertNull(worker.poll());
        assertThrows(IllegalStateException.class, () -> worker.start(2, 1,
                MobileActivationHttpProxy.RequestKind.SESSION, foundationRequest()));
    }

    @Test
    public void exact302RestoresPostHeadersAndBody() throws Exception {
        RecordingExchange exchange =
                new RecordingExchange(
                        new MobileActivationHttpProxy.HttpResponse(
                                302,
                                Map.of(
                                        "Location",
                                        "https://redirect.invalid/next"),
                                new byte[] {
                                        9
                                }),
                        new MobileActivationHttpProxy.HttpResponse(
                                200,
                                Map.of(
                                        "Content-Type",
                                        "application/x-apple-plist",
                                        "X-Reply",
                                        "ok"),
                                new byte[] {
                                        5,
                                        6
                                }));
        MobileActivationHttpProxy proxy =
                new MobileActivationHttpProxy(
                        exchange);

        MobileActivationHttpProxy.ProxyResponse response =
                proxy.execute(
                        MobileActivationHttpProxy.RequestKind.SESSION,
                        foundationRequest());
        try {
            assertEquals(
                    200,
                    response.statusCode());
            assertArrayEquals(
                    new byte[] {
                            5,
                            6
                    },
                    response.body());
            assertEquals(
                    Map.of(
                            "Content-Type",
                            "application/x-apple-plist",
                            "X-Reply",
                            "ok"),
                    ActivationArchiveCodec.decodeResponseHeaders(
                            response.archivedHeaders()));
            PbBridgeCodec.ActivationData message =
                    response.toPbBridgeMessage();
            try {
                assertArrayEquals(
                        response.body(),
                        message.activationData());
                assertArrayEquals(
                        response.archivedHeaders(),
                        message.archivedResponseHeaders());
            } finally {
                message.destroy();
            }
        } finally {
            response.destroy();
        }

        assertEquals(
                2,
                exchange.seen.size());
        SeenRequest initial =
                exchange.seen.get(
                        0);
        assertEquals(
                "POST",
                initial.method);
        assertEquals(
                "WiFi",
                header(
                        initial.headers,
                        "X-iOS-Activation-Medium"));
        assertArrayEquals(
                new byte[] {
                        1,
                        2,
                        3,
                        4
                },
                initial.body);

        SeenRequest redirected =
                exchange.seen.get(
                        1);
        assertEquals(
                URI.create(
                        "https://redirect.invalid/next"),
                redirected.url);
        assertEquals(
                "POST",
                redirected.method);
        assertEquals(
                "application/x-apple-plist",
                header(
                        redirected.headers,
                        "Content-Type"));
        assertEquals(
                "4",
                header(
                        redirected.headers,
                        "Content-Length"));
        assertEquals(
                "ArchiveFixture/1",
                header(
                        redirected.headers,
                        "User-Agent"));
        assertArrayEquals(
                initial.body,
                redirected.body);
    }

    @Test
    public void non302LegacyRedirectKeepsProposedGet() throws Exception {
        RecordingExchange exchange =
                new RecordingExchange(
                        new MobileActivationHttpProxy.HttpResponse(
                                301,
                                Map.of(
                                        "Location",
                                        "/moved"),
                                new byte[] {
                                        1
                                }),
                        new MobileActivationHttpProxy.HttpResponse(
                                200,
                                Map.of(
                                        "Content-Type",
                                        "application/x-apple-plist"),
                                new byte[] {
                                        2
                                }));
        MobileActivationHttpProxy.ProxyResponse response =
                new MobileActivationHttpProxy(
                        exchange).execute(
                        MobileActivationHttpProxy.RequestKind.SESSION,
                        foundationRequest());
        response.destroy();

        SeenRequest redirected =
                exchange.seen.get(
                        1);
        assertEquals(
                "GET",
                redirected.method);
        assertNull(
                redirected.body);
        assertNull(
                header(
                        redirected.headers,
                        "Content-Type"));
        assertNull(
                header(
                        redirected.headers,
                        "Content-Length"));
    }

    @Test
    public void retriesOneIdenticalRequestAfterIoFailure() throws Exception {
        RecordingExchange exchange =
                new RecordingExchange(
                        new IOException(
                                "fixture failure"),
                        new MobileActivationHttpProxy.HttpResponse(
                                200,
                                Map.of(
                                        "Content-Type",
                                        "application/x-apple-plist"),
                                new byte[] {
                                        7
                                }));
        MobileActivationHttpProxy.ProxyResponse response =
                new MobileActivationHttpProxy(
                        exchange).execute(
                        MobileActivationHttpProxy.RequestKind.SESSION,
                        foundationRequest());
        response.destroy();

        assertEquals(
                2,
                exchange.seen.size());
        SeenRequest first =
                exchange.seen.get(
                        0);
        SeenRequest second =
                exchange.seen.get(
                        1);
        assertEquals(
                first.url,
                second.url);
        assertEquals(
                first.method,
                second.method);
        assertEquals(
                first.headers,
                second.headers);
        assertArrayEquals(
                first.body,
                second.body);
    }

    @Test
    public void httpErrorPolicyDiffersByRequestKind() throws Exception {
        MobileActivationHttpProxy.ProxyResponse sessionResponse =
                new MobileActivationHttpProxy(
                        new RecordingExchange(
                                errorResponse())).execute(
                        MobileActivationHttpProxy.RequestKind.SESSION,
                        foundationRequest());
        try {
            assertEquals(
                    500,
                    sessionResponse.statusCode());
        } finally {
            sessionResponse.destroy();
        }

        MobileActivationHttpProxy.ActivationRejectedException rejected =
                assertThrows(
                        MobileActivationHttpProxy
                                .ActivationRejectedException.class,
                        () -> new MobileActivationHttpProxy(
                                new RecordingExchange(
                                        errorResponse())).execute(
                                MobileActivationHttpProxy
                                        .RequestKind.ACTIVATION,
                                foundationRequest()));
        assertEquals(
                500,
                rejected.statusCode());
    }

    @Test
    public void unsafeRedirectFailsBeforeSecondExchange() {
        RecordingExchange exchange =
                new RecordingExchange(
                        new MobileActivationHttpProxy.HttpResponse(
                                302,
                                Map.of(
                                        "Location",
                                        "http://redirect.invalid/plaintext"),
                                new byte[] {
                                        1
                                }));
        assertThrows(
                IOException.class,
                () -> new MobileActivationHttpProxy(
                        exchange).execute(
                        MobileActivationHttpProxy.RequestKind.SESSION,
                        foundationRequest()));
        assertEquals(
                1,
                exchange.seen.size());
    }

    private static MobileActivationHttpProxy.HttpResponse errorResponse() {
        return new MobileActivationHttpProxy.HttpResponse(
                500,
                Map.of(
                        "Content-Type",
                        "application/x-apple-plist"),
                new byte[] {
                        8
                });
    }

    private static byte[] foundationRequest() {
        return Base64.getMimeDecoder().decode(
                ActivationArchiveCodecTest.FOUNDATION_REQUEST_ARCHIVE);
    }

    private static String header(
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

    private static final class RecordingExchange
            implements MobileActivationHttpProxy.Exchange {
        private final List<Object> outcomes =
                new ArrayList<>();
        private final List<SeenRequest> seen =
                new ArrayList<>();

        private RecordingExchange(
                Object... outcomes) {
            Collections.addAll(
                    this.outcomes,
                    outcomes);
        }

        @Override
        public MobileActivationHttpProxy.HttpResponse execute(
                MobileActivationHttpProxy.HttpRequest request)
                throws IOException {
            seen.add(
                    new SeenRequest(
                            request.url(),
                            request.method(),
                            request.headers(),
                            request.body()));
            assertFalse(
                    outcomes.isEmpty());
            Object outcome =
                    outcomes.remove(
                            0);
            if (outcome instanceof IOException failure) {
                throw failure;
            }
            return (MobileActivationHttpProxy.HttpResponse) outcome;
        }
    }

    private static final class SeenRequest {
        private final URI url;
        private final String method;
        private final Map<String, String> headers;
        private final byte[] body;

        private SeenRequest(
                URI url,
                String method,
                Map<String, String> headers,
                byte[] body) {
            this.url = url;
            this.method = method;
            this.headers =
                    new LinkedHashMap<>(
                            headers);
            this.body =
                    body == null
                            ? null
                            : body.clone();
        }
    }
}

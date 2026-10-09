package dev.applewatchandroid.bridge;


import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Owns the Activation Lock (buddyml) challenge exchange between the
 * root HAL session, the foreground UI and Albert.
 *
 * <p>When Albert answers the Watch activation POST with a buddyml page
 * (e.g. {@code FMIPLockChallenge}), the response must not reach the Watch:
 * its mobileactivation daemon cannot parse it and fails with
 * "Failed to extract activation record". A real iPhone renders the page,
 * collects the owner credentials and re-POSTs until Albert returns the
 * activation record. This manager mirrors that phone-side loop, including
 * alert pages (rejected credentials, disabled account) and field-less
 * "Please retry activation" pages, which are answered by replaying the
 * original deviceActivation POST rebuilt from the page's serverInfo echo.</p>
 *
 * <p>Wire contract with MainActivity (stdout/stdin pipes, no shared
 * process):</p>
 * <ul>
 *   <li>root -&gt; UI: {@code ACTIVATION-CHALLENGE-V1:<id>:<page>:<b64 account>:<f1,f2>[:retry]}</li>
 *   <li>root -&gt; UI: {@code ACTIVATION-ALERT-V1:<id>:<b64 title>:<b64 message>}</li>
 *   <li>UI -&gt; root: {@code ACTIVATION-CREDENTIALS-V1:<id>:<f1>=<b64>:<f2>=<b64>}</li>
 *   <li>UI -&gt; root: {@code ACTIVATION-RETRY-V1:<id>}</li>
 *   <li>UI -&gt; root: {@code ACTIVATION-CANCEL-V1:<id>}</li>
 * </ul>
 *
 * <p>Credentials exist only as in-memory char arrays, are wiped after every
 * use and are never logged or persisted.</p>
 */
final class ActivationChallengeManager {
    static final String CHALLENGE_LINE_PREFIX =
            "ACTIVATION-CHALLENGE-V1:";
    static final String ALERT_LINE_PREFIX =
            "ACTIVATION-ALERT-V1:";
    static final String CREDENTIALS_LINE_PREFIX =
            "ACTIVATION-CREDENTIALS-V1:";
    static final String RETRY_LINE_PREFIX =
            "ACTIVATION-RETRY-V1:";
    static final String CANCEL_LINE_PREFIX =
            "ACTIVATION-CANCEL-V1:";

    private static final int MAX_AUTO_REPLAYS =
            2;

    private static final Object LOCK =
            new Object();
    private static Consumer<String> uiEmitter;
    private static Consumer<String> logger;
    private static Pending pending;
    private static Resolution resolution;
    private static int nextChallengeId =
            1;

    private ActivationChallengeManager() {
    }

    static void attach(
            Consumer<String> ui,
            Consumer<String> log) {
        synchronized (LOCK) {
            uiEmitter =
                    ui;
            logger =
                    log;
            pending =
                    null;
            resolution =
                    null;
        }
    }

    static void detach() {
        synchronized (LOCK) {
            uiEmitter =
                    null;
            logger =
                    null;
            Pending stale =
                    pending;
            pending =
                    null;
            if (stale != null) {
                stale.destroy();
            }
        }
    }

    private static void log(
            String message) {
        Consumer<String> sink;
        synchronized (LOCK) {
            sink =
                    logger;
        }
        if (sink != null) {
            try {
                sink.accept(
                        message);
            } catch (RuntimeException ignored) {
                // Diagnostics must never affect the session.
            }
        }
    }

    /**
     * Called from the HAL receive loop when Albert answered the ACTIVATION
     * request with any buddyml page. FORM pages prompt the owner, ALERT
     * pages surface the server message, RETRY pages replay the original
     * activation POST automatically.
     */
    static void offerPage(
            ActivationChallengeCodec.PageInfo page,
            long generation,
            int attempt) {
        if (page == null) {
            throw new IllegalArgumentException(
                    "Activation page is absent");
        }
        switch (page.kind) {
            case FORM ->
                    offerForm(
                            page,
                            generation,
                            attempt,
                            false);
            case ALERT ->
                    offerAlert(
                            page,
                            generation,
                            attempt,
                            null,
                            null);
            case RETRY ->
                    handleRetryPage(
                            page,
                            generation,
                            attempt);
            default ->
                    failResolution(
                            generation,
                            attempt,
                            "unsupported buddyml page");
        }
    }

    private static void offerForm(
            ActivationChallengeCodec.PageInfo page,
            long generation,
            int attempt,
            boolean isRetryPrompt) {
        Consumer<String> emitter;
        int id;
        synchronized (LOCK) {
            if (pending != null
                    && pending.generation == generation
                    && pending.attempt == attempt
                    && pending.page.kind
                            == ActivationChallengeCodec.PageKind.FORM
                    && java.util.Objects.equals(
                            pending.page.pageName,
                            page.pageName)
                    && !isRetryPrompt) {
                // Same challenge already presented (in flight or awaiting
                // owner input); a duplicate Watch retry must not reset
                // the dialog.
                return;
            }
            Pending stale =
                    pending;
            pending =
                    null;
            if (stale != null) {
                stale.destroy();
            }
            id =
                    nextChallengeId++;
            pending =
                    new Pending(
                            id,
                            page,
                            generation,
                            attempt,
                            page.activationInfoBase64,
                            page.submitUrl);
            emitter =
                    uiEmitter;
        }
        StringBuilder line =
                new StringBuilder(
                        256);
        line.append(
                CHALLENGE_LINE_PREFIX);
        line.append(
                id);
        line.append(
                ':');
        line.append(
                page.pageName == null
                        ? "form"
                        : page.pageName);
        line.append(
                ':');
        line.append(
                page.maskedAccount == null
                        ? "-"
                        : BridgeBase64.encodeToString(
                                page.maskedAccount.getBytes(
                                        StandardCharsets.UTF_8),
                                BridgeBase64.NO_WRAP));
        line.append(
                ':');
        StringBuilder fieldList =
                new StringBuilder();
        for (String field :
                page.fields) {
            if (fieldList.length() > 0) {
                fieldList.append(
                        ',');
            }
            fieldList.append(
                    field);
        }
        line.append(
                fieldList);
        if (isRetryPrompt) {
            line.append(
                    ":retry");
        }
        if (emitter != null) {
            try {
                emitter.accept(
                        line.toString());
            } catch (RuntimeException ignored) {
                // UI delivery is best-effort; the journal still has the event.
            }
        }
        log("ACTIVATION CHALLENGE: Albert requires owner credentials; page="
                + page.pageName
                + " accountHint="
                + (page.maskedAccount == null
                        ? "none"
                        : page.maskedAccount)
                + " fields="
                + fieldList
                + (isRetryPrompt
                        ? " (re-prompt after a rejected attempt)"
                        : "")
                + "; Watch keeps waiting, buddyml was not forwarded.");
    }

    private static void offerAlert(
            ActivationChallengeCodec.PageInfo page,
            long generation,
            int attempt,
            String carriedActivationInfo,
            String carriedSubmitUrl) {
        Consumer<String> emitter;
        int id;
        String title =
                page.title == null
                        ? "Albert"
                        : page.title;
        String message =
                page.message == null
                        ? ""
                        : page.message;
        synchronized (LOCK) {
            Pending stale =
                    pending;
            String info =
                    carriedActivationInfo;
            String url =
                    carriedSubmitUrl;
            if (stale != null
                    && stale.generation == generation
                    && stale.attempt == attempt) {
                if (info == null) {
                    info =
                            stale.activationInfoBase64;
                }
                if (url == null) {
                    url =
                            stale.submitUrl;
                }
            }
            pending =
                    null;
            if (stale != null) {
                stale.destroy();
            }
            id =
                    nextChallengeId++;
            pending =
                    new Pending(
                            id,
                            page,
                            generation,
                            attempt,
                            info,
                            url);
            emitter =
                    uiEmitter;
        }
        String line =
                ALERT_LINE_PREFIX
                        + id
                        + ':'
                        + BridgeBase64.encodeToString(
                                title.getBytes(
                                        StandardCharsets.UTF_8),
                                BridgeBase64.NO_WRAP)
                        + ':'
                        + BridgeBase64.encodeToString(
                                message.getBytes(
                                        StandardCharsets.UTF_8),
                                BridgeBase64.NO_WRAP);
        if (emitter != null) {
            try {
                emitter.accept(
                        line);
            } catch (RuntimeException ignored) {
                // UI delivery is best-effort; the journal still has the event.
            }
        }
        log("ACTIVATION CHALLENGE ALERT: title=\"" + title
                + "\" message=\"" + message
                + "\"; awaiting the owner (retry replays activation).");
    }

    private static void handleRetryPage(
            ActivationChallengeCodec.PageInfo page,
            long generation,
            int attempt) {
        String activationInfo;
        String submitUrl;
        synchronized (LOCK) {
            Pending stale =
                    pending;
            String carriedInfo =
                    stale != null
                            && stale.generation == generation
                            && stale.attempt == attempt
                            ? stale.activationInfoBase64
                            : null;
            String carriedUrl =
                    stale != null
                            && stale.generation == generation
                            && stale.attempt == attempt
                            ? stale.submitUrl
                            : null;
            pending =
                    null;
            if (stale != null) {
                stale.destroy();
            }
            activationInfo =
                    page.activationInfoBase64 != null
                            ? page.activationInfoBase64
                            : carriedInfo;
            submitUrl =
                    page.submitUrl != null
                            ? page.submitUrl
                            : carriedUrl;
        }
        if (activationInfo == null) {
            log("ACTIVATION CHALLENGE RETRY: page \"" + page.title
                    + "\" has no activation-info echo; cannot replay, "
                    + "the Watch may re-request activation.");
            failResolution(
                    generation,
                    attempt,
                    "retry page without activation-info");
            return;
        }
        log("ACTIVATION CHALLENGE RETRY: \"" + page.title
                + "\" / \"" + page.message
                + "\"; replaying the original activation POST.");
        startReplayWorker(
                generation,
                attempt,
                activationInfo,
                submitUrl,
                0);
    }

    /**
     * Parses the UI credentials line. Format:
     * {@code ACTIVATION-CREDENTIALS-V1:<id>:<field>=<b64>[:<field>=<b64>...]}.
     */
    static void acceptCredentialsLine(
            String line) {
        Map<String, char[]> values =
                new LinkedHashMap<>();
        int challengeId =
                -1;
        try {
            String rest =
                    line.substring(
                            CREDENTIALS_LINE_PREFIX.length());
            String[] parts =
                    rest.split(
                            ":");
            if (parts.length < 2) {
                throw new IllegalArgumentException(
                        "credentials payload is incomplete");
            }
            challengeId =
                    Integer.parseInt(
                            parts[0]);
            for (int index = 1;
                    index < parts.length;
                    index++) {
                String pair =
                        parts[index];
                int eq =
                        pair.indexOf(
                                '=');
                if (eq <= 0) {
                    continue;
                }
                String field =
                        pair.substring(
                                0,
                                eq);
                byte[] decoded =
                        BridgeBase64.decode(
                                pair.substring(
                                        eq + 1),
                                BridgeBase64.NO_WRAP);
                char[] chars =
                        new String(
                                decoded,
                                StandardCharsets.UTF_8).toCharArray();
                Arrays.fill(
                        decoded,
                        (byte) 0);
                values.put(
                        field,
                        chars);
            }
        } catch (RuntimeException malformed) {
            wipeValues(
                    values);
            log("ACTIVATION CHALLENGE: credentials input rejected (malformed).");
            return;
        }
        submitCredentials(
                challengeId,
                values);
    }

    /**
     * Parses the UI retry line ({@code ACTIVATION-RETRY-V1:<id>}); replays
     * the original activation POST so Albert issues a fresh challenge.
     */
    static void acceptRetryLine(
            String line) {
        int challengeId;
        try {
            challengeId =
                    Integer.parseInt(
                            line.substring(
                                    RETRY_LINE_PREFIX.length())
                                    .trim());
        } catch (RuntimeException malformed) {
            return;
        }
        Pending current;
        synchronized (LOCK) {
            if (pending == null
                    || pending.id != challengeId
                    || pending.workerRunning) {
                return;
            }
            pending.workerRunning =
                    true;
            current =
                    pending;
        }
        log("ACTIVATION CHALLENGE: owner asked to retry; replaying the "
                + "original activation POST.");
        startReplayWorker(
                current,
                0);
    }

    /**
     * Parses the UI cancel line ({@code ACTIVATION-CANCEL-V1:<id>}); the
     * pending Albert exchange resolves as a user cancellation.
     */
    static void acceptCancelLine(
            String line) {
        int challengeId;
        try {
            challengeId =
                    Integer.parseInt(
                            line.substring(
                                    CANCEL_LINE_PREFIX.length())
                                    .trim());
        } catch (RuntimeException malformed) {
            return;
        }
        synchronized (LOCK) {
            if (pending == null
                    || pending.id != challengeId) {
                return;
            }
            Pending cancelled =
                    pending;
            pending =
                    null;
            long generation =
                    cancelled.generation;
            int attempt =
                    cancelled.attempt;
            cancelled.destroy();
            resolution =
                    Resolution.cancelled(
                            generation,
                            attempt);
        }
        log("ACTIVATION CHALLENGE: owner cancelled the unlock; "
                + "Albert exchange resolves as cancelled.");
    }

    private static void submitCredentials(
            int challengeId,
            Map<String, char[]> values) {
        Pending current;
        synchronized (LOCK) {
            if (pending == null
                    || pending.id != challengeId
                    || pending.workerRunning
                    || pending.page.kind
                            != ActivationChallengeCodec.PageKind.FORM) {
                wipeValues(
                        values);
                return;
            }
            pending.workerRunning =
                    true;
            current =
                    pending;
        }
        Thread worker =
                new Thread(
                        () -> runSubmit(
                                current,
                                values),
                        "watch-activation-challenge");
        worker.setDaemon(
                true);
        worker.start();
    }

    private static void runSubmit(
            Pending submitted,
            Map<String, char[]> values) {
        byte[] formBody =
                null;
        MobileActivationHttpProxy.ProxyResponse response =
                null;
        try {
            formBody =
                    ActivationChallengeCodec.buildSubmitBody(
                            submitted.page.toFormChallenge(),
                            values);
            URI submitUrl =
                    new URI(
                            "https://albert.apple.com").resolve(
                            submitted.submitUrl == null
                                    ? "/deviceservices/deviceActivation"
                                    : submitted.submitUrl);
            response =
                    MobileActivationHttpProxy.executeChallengeSubmit(
                            submitUrl,
                            formBody);
            byte[] body =
                    response.body();
            if (ActivationChallengeCodec.isChallengePage(
                    body)) {
                ActivationChallengeCodec.PageInfo followUp =
                        ActivationChallengeCodec.classify(
                                body);
                response.destroy();
                response =
                        null;
                handleFollowUpPage(
                        submitted,
                        followUp);
                return;
            }
            byte[] responseBody =
                    response.body();
            byte[] responseHeaders =
                    response.archivedHeaders();
            int statusCode =
                    response.statusCode();
            synchronized (LOCK) {
                if (pending != submitted) {
                    wipe(
                            responseBody);
                    wipe(
                            responseHeaders);
                    return;
                }
                pending =
                        null;
                submitted.destroy();
                resolution =
                        Resolution.success(
                                submitted.generation,
                                submitted.attempt,
                                statusCode,
                                responseBody,
                                responseHeaders);
            }
            log("ACTIVATION CHALLENGE: Albert returned a non-buddyml "
                    + "response (status="
                    + statusCode
                    + " bodyBytes="
                    + responseBody.length
                    + "); injecting it as the pending Watch activation "
                    + "answer.");
        } catch (Exception error) {
            synchronized (LOCK) {
                if (pending == submitted) {
                    pending =
                            null;
                    submitted.destroy();
                    resolution =
                            Resolution.failed(
                                    submitted.generation,
                                    submitted.attempt,
                                    error.getMessage() == null
                                            ? "challenge submit failed"
                                            : error.getMessage());
                }
            }
            log("ACTIVATION CHALLENGE FAIL: "
                    + (error.getMessage() == null
                            ? "challenge submit failed"
                            : error.getMessage()));
        } finally {
            wipeValues(
                    values);
            wipe(
                    formBody);
            if (response != null) {
                response.destroy();
            }
        }
    }

    /**
     * Routes a buddyml follow-up page after a credentials submit or a
     * replay: forms are re-offered (marked as a rejected-attempt re-prompt),
     * alerts are surfaced, retry pages replay the original POST (bounded).
     */
    private static void handleFollowUpPage(
            Pending submitted,
            ActivationChallengeCodec.PageInfo followUp) {
        long generation =
                submitted.generation;
        int attempt =
                submitted.attempt;
        String activationInfo =
                followUp.activationInfoBase64 != null
                        ? followUp.activationInfoBase64
                        : submitted.activationInfoBase64;
        String submitUrl =
                followUp.submitUrl != null
                        ? followUp.submitUrl
                        : submitted.submitUrl;
        synchronized (LOCK) {
            if (pending != submitted) {
                return;
            }
            pending =
                    null;
            submitted.destroy();
        }
        switch (followUp.kind) {
            case FORM -> {
                log("ACTIVATION CHALLENGE: Albert returned a follow-up "
                        + "credentials page; prompting again (previous "
                        + "attempt was rejected).");
                offerForm(
                        followUp,
                        generation,
                        attempt,
                        true);
            }
            case ALERT ->
                    offerAlert(
                            followUp,
                            generation,
                            attempt,
                            activationInfo,
                            submitUrl);
            case RETRY -> {
                log("ACTIVATION CHALLENGE: Albert answered \""
                        + followUp.title
                        + "\"; replaying the original activation POST.");
                startReplayWorker(
                        generation,
                        attempt,
                        activationInfo,
                        submitUrl,
                        0);
            }
            default ->
                    failResolution(
                            generation,
                            attempt,
                            "unsupported follow-up page");
        }
    }

    private static void startReplayWorker(
            long generation,
            int attempt,
            String activationInfo,
            String submitUrl,
            int replaysUsed) {
        Thread worker =
                new Thread(
                        () -> runReplay(
                                generation,
                                attempt,
                                activationInfo,
                                submitUrl,
                                replaysUsed),
                        "watch-activation-replay");
        worker.setDaemon(
                true);
        worker.start();
    }

    private static void startReplayWorker(
            Pending current,
            int replaysUsed) {
        long generation =
                current.generation;
        int attempt =
                current.attempt;
        String activationInfo =
                current.activationInfoBase64;
        String submitUrl =
                current.submitUrl;
        synchronized (LOCK) {
            if (pending == current) {
                pending =
                        null;
                current.destroy();
            }
        }
        if (activationInfo == null) {
            failResolution(
                    generation,
                    attempt,
                    "retry requested without activation-info");
            return;
        }
        startReplayWorker(
                generation,
                attempt,
                activationInfo,
                submitUrl,
                replaysUsed);
    }

    private static void runReplay(
            long generation,
            int attempt,
            String activationInfo,
            String submitUrl,
            int replaysUsed) {
        byte[] formBody =
                null;
        MobileActivationHttpProxy.ProxyResponse response =
                null;
        try {
            formBody =
                    ActivationChallengeCodec.buildRetryBody(
                            activationInfo);
            URI url =
                    new URI(
                            "https://albert.apple.com").resolve(
                            submitUrl == null
                                    ? "/deviceservices/deviceActivation"
                                    : submitUrl);
            response =
                    MobileActivationHttpProxy.executeChallengeSubmit(
                            url,
                            formBody);
            byte[] body =
                    response.body();
            if (ActivationChallengeCodec.isChallengePage(
                    body)) {
                ActivationChallengeCodec.PageInfo followUp =
                        ActivationChallengeCodec.classify(
                                body);
                response.destroy();
                response =
                        null;
                switch (followUp.kind) {
                    case FORM ->
                            offerForm(
                                    followUp,
                                    generation,
                                    attempt,
                                    false);
                    case ALERT ->
                            offerAlert(
                                    followUp,
                                    generation,
                                    attempt,
                                    activationInfo,
                                    submitUrl);
                    case RETRY -> {
                        if (replaysUsed >= MAX_AUTO_REPLAYS) {
                            log("ACTIVATION CHALLENGE RETRY: Albert keeps "
                                    + "asking to retry; surfacing the page "
                                    + "to the owner.");
                            offerAlert(
                                    followUp,
                                    generation,
                                    attempt,
                                    activationInfo,
                                    submitUrl);
                        } else {
                            log("ACTIVATION CHALLENGE RETRY: replay #"
                                    + (replaysUsed + 2)
                                    + " after another retry page.");
                            startReplayWorker(
                                    generation,
                                    attempt,
                                    activationInfo,
                                    submitUrl,
                                    replaysUsed + 1);
                        }
                    }
                    default ->
                            failResolution(
                                    generation,
                                    attempt,
                                    "unsupported replay follow-up");
                }
                return;
            }
            byte[] responseBody =
                    response.body();
            byte[] responseHeaders =
                    response.archivedHeaders();
            int statusCode =
                    response.statusCode();
            boolean stillCurrent;
            synchronized (LOCK) {
                stillCurrent =
                        pending == null
                                || (pending.generation == generation
                                && pending.attempt == attempt);
                if (stillCurrent) {
                    Pending stale =
                            pending;
                    pending =
                            null;
                    if (stale != null) {
                        stale.destroy();
                    }
                    resolution =
                            Resolution.success(
                                    generation,
                                    attempt,
                                    statusCode,
                                    responseBody,
                                    responseHeaders);
                }
            }
            if (!stillCurrent) {
                wipe(
                        responseBody);
                wipe(
                        responseHeaders);
                return;
            }
            log("ACTIVATION CHALLENGE: replay returned a non-buddyml "
                    + "response (status="
                    + statusCode
                    + " bodyBytes="
                    + responseBody.length
                    + "); injecting it as the pending Watch activation "
                    + "answer.");
        } catch (Exception error) {
            failResolution(
                    generation,
                    attempt,
                    error.getMessage() == null
                            ? "activation replay failed"
                            : error.getMessage());
        } finally {
            wipe(
                    formBody);
            if (response != null) {
                response.destroy();
            }
        }
    }

    private static void failResolution(
            long generation,
            int attempt,
            String message) {
        synchronized (LOCK) {
            Pending stale =
                    pending;
            if (stale != null
                    && stale.generation == generation
                    && stale.attempt == attempt) {
                pending =
                        null;
                stale.destroy();
            }
            resolution =
                    Resolution.failed(
                            generation,
                            attempt,
                            message);
        }
        log("ACTIVATION CHALLENGE FAIL: " + message);
    }

    /** Polled by the HAL receive loop alongside the Albert worker. */
    static Resolution pollResolution() {
        synchronized (LOCK) {
            Resolution result =
                    resolution;
            resolution =
                    null;
            return result;
        }
    }

    private static void wipeValues(
            Map<String, char[]> values) {
        if (values == null) {
            return;
        }
        for (char[] value :
                values.values()) {
            if (value != null) {
                Arrays.fill(
                        value,
                        '\0');
            }
        }
        values.clear();
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    private static final class Pending {
        final int id;
        final ActivationChallengeCodec.PageInfo page;
        final long generation;
        final int attempt;
        /** Carried from the credentials form for replay; may be null. */
        final String activationInfoBase64;
        final String submitUrl;
        boolean workerRunning;

        Pending(
                int id,
                ActivationChallengeCodec.PageInfo page,
                long generation,
                int attempt,
                String activationInfoBase64,
                String submitUrl) {
            this.id =
                    id;
            this.page =
                    page;
            this.generation =
                    generation;
            this.attempt =
                    attempt;
            this.activationInfoBase64 =
                    activationInfoBase64;
            this.submitUrl =
                    submitUrl;
        }

        void destroy() {
            // Page strings contain no credentials; nothing to wipe.
        }
    }

    static final class Resolution {
        final long generation;
        final int attempt;
        final int statusCode;
        final byte[] body;
        final byte[] archivedHeaders;
        final String failure;
        final boolean cancelled;

        private Resolution(
                long generation,
                int attempt,
                int statusCode,
                byte[] body,
                byte[] archivedHeaders,
                String failure,
                boolean cancelled) {
            this.generation =
                    generation;
            this.attempt =
                    attempt;
            this.statusCode =
                    statusCode;
            this.body =
                    body;
            this.archivedHeaders =
                    archivedHeaders;
            this.failure =
                    failure;
            this.cancelled =
                    cancelled;
        }

        static Resolution success(
                long generation,
                int attempt,
                int statusCode,
                byte[] body,
                byte[] archivedHeaders) {
            return new Resolution(
                    generation,
                    attempt,
                    statusCode,
                    body,
                    archivedHeaders,
                    null,
                    false);
        }

        static Resolution failed(
                long generation,
                long attempt,
                String message) {
            return new Resolution(
                    generation,
                    (int) attempt,
                    -1,
                    null,
                    null,
                    message,
                    false);
        }

        static Resolution cancelled(
                long generation,
                int attempt) {
            return new Resolution(
                    generation,
                    attempt,
                    -1,
                    null,
                    null,
                    "owner cancelled",
                    true);
        }

        boolean isSuccess() {
            return body != null
                    && archivedHeaders != null
                    && failure == null
                    && !cancelled;
        }

        void destroy() {
            wipe(
                    body);
            wipe(
                    archivedHeaders);
        }
    }
}

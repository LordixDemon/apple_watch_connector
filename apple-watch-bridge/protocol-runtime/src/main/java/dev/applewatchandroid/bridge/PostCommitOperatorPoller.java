package dev.applewatchandroid.bridge;

import java.util.Locale;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Activation proxy/challenge and operator-override pollers for
 * {@link PostCommitActivationRunner}.
 */
final class PostCommitOperatorPoller {
    private final HalTransportSession session;
    private final PostCommitActivationRunner runner;

    PostCommitOperatorPoller(
            HalTransportSession session,
            PostCommitActivationRunner runner) {
        this.session = session;
        this.runner = runner;
    }

    void pollActivationProxy(int remoteCid) throws Exception {
        if (runner.activationProxyWorker == null) return;
        ActivationProxyWorker.Result result = runner.activationProxyWorker.poll();
        if (result != null) {
            try (result) {
                if (runner.activePostCommitCoordinator == null
                        || runner.activePostCommitCoordinator.snapshot().generation != result.generation) return;
                if (result.failure == null) {
                    MobileActivationHttpProxy.ProxyResponse response =
                            result.takeResponse();
                    if (result.kind
                            == MobileActivationHttpProxy.RequestKind.ACTIVATION
                            && ActivationChallengeCodec.isChallengePage(
                            response.body())) {
                        // Albert answered with a buddyml page
                        // (FMIPLockChallenge credentials form, an alert
                        // or a retry page). Forwarding any of them to
                        // the Watch only produced MA "Failed to extract
                        // activation record"; hold the Watch request and
                        // resolve the page with the owner instead.
                        ActivationChallengeCodec.PageInfo page =
                                ActivationChallengeCodec.classify(
                                        response.body());
                        captureActivationArtifact(
                                "challenge-page",
                                response.body());
                        ActivationChallengeManager.offerPage(
                                page,
                                result.generation,
                                result.attempt);
                        response.destroy();
                        return;
                    }
                    if (result.kind
                            == MobileActivationHttpProxy.RequestKind.ACTIVATION) {
                        captureActivationArtifact(
                                "activation-raw-body",
                                response.body());
                        captureActivationArtifact(
                                "activation-raw-headers",
                                response.archivedHeaders());
                        response = canonicalizeActivationResponse(response);
                    }
                    if (runner.pendingActivationResponse != null) runner.pendingActivationResponse.destroy();
                    runner.pendingActivationResponse = response;
                    log("ACTIVATION HTTPS RESPONSE: status=" + runner.pendingActivationResponse.statusCode()
                            + " kind=" + result.kind
                            + " bodyBytes=" + runner.pendingActivationResponse.body().length
                            + " bodyPreview=\"" + printablePreview(runner.pendingActivationResponse.body(), 512)
                            + "\".");
                    captureActivationArtifact(
                            result.kind.name().toLowerCase(Locale.US) + "-response-body",
                            runner.pendingActivationResponse.body());
                    captureActivationArtifact(
                            result.kind.name().toLowerCase(Locale.US) + "-response-headers",
                            runner.pendingActivationResponse.archivedHeaders());
                } else {
                    log("ACTIVATION PROXY FAIL: " + safeMessage(result.failure));
                }
                runner.executePostCommitActions(runner.activePostCommitCoordinator.onActivationProxyResult(
                        result.generation, result.attempt, result.kind, result.failure == null), remoteCid);
            }
        }
        pollActivationChallenge(remoteCid);
        pollForceActivationConfirmed(remoteCid);
        pollRedriveActivation(remoteCid);
        pollForceSetupObserved(remoteCid);
        pollRetryActivation(remoteCid);
        pollPublishPairedSync(remoteCid);
        pollSyncProgress(remoteCid);
    }

    void pollSyncProgress(int remoteCid) throws Exception {
        if (runner.activePostCommitAdapter == null
                || runner.activePostCommitCoordinator == null) {
            return;
        }
        if (!runner.syncProgressAutoSent
                && runner.activePostCommitCoordinator.snapshot().initialSyncPrepared) {
            runner.syncProgressAutoSent = true;
            PENDING_SYNC_PROGRESS.add(new double[] {1.0, 3});
            log("SYNC PROGRESS: Watch answered PrepareInitialSync; sending completion update (progress=1.0 state=3).");
        }
        double[] next;
        while ((next = PENDING_SYNC_PROGRESS.poll()) != null) {
            AppleWatchPostCommitIdsAdapter.DiagnosticSend send =
                    runner.activePostCommitAdapter.prepareDiagnosticSyncProgress(
                            next[0],
                            (int) next[1]);
            try {
                session.normalLink.sendNormalFrames(remoteCid, send.outboundFrames(), "SYNC_PROGRESS");
                log("SYNC PROGRESS TX: PBBridge type 19 progress=" + next[0]
                        + " state=" + (int) next[1] + " topic=" + send.topic
                        + " sequence=" + send.sequence + ".");
            } finally {
                send.close();
            }
        }
    }

    void pollPublishPairedSync(int remoteCid) throws Exception {
        if (!PENDING_PUBLISH_PAIRED_SYNC.compareAndSet(true, false)) return;
        if (runner.activePostCommitAdapter == null
                || runner.activePostCommitCoordinator == null) {
            log("PUBLISH PAIRED SYNC: no active post-commit session; diagnostic republish ignored.");
            return;
        }
        double now = (System.currentTimeMillis() / 1000.0) - 978307200.0;
        AppleWatchPostCommitIdsAdapter.DiagnosticSend send =
                runner.activePostCommitAdapter.prepareDiagnosticPairedSyncRepublish(now);
        try {
            session.normalLink.sendNormalFrames(remoteCid, send.outboundFrames(), "DIAG PUBLISH_PAIRED_SYNC");
            log("PUBLISH PAIRED SYNC: diagnostic completion republished on the live session; topic="
                    + send.topic + " sequence=" + send.sequence + ".");
        } finally {
            send.close();
        }
    }

    void pollForceActivationConfirmed(int remoteCid) throws Exception {
        if (!PENDING_FORCE_ACTIVATION_CONFIRMED.compareAndSet(true, false)) return;
        if (runner.activePostCommitCoordinator == null) {
            log("FORCE ACTIVATION CONFIRMED: no active post-commit coordinator; override ignored.");
            return;
        }
        long generation = runner.activePostCommitCoordinator.snapshot().generation;
        log("FORCE ACTIVATION CONFIRMED: owner override — Albert 200 record was delivered and the Watch reported no rejection; checkpointing ACTIVATION_CONFIRMED to resume the setup flow.");
        runner.executePostCommitActions(runner.activePostCommitCoordinator.onActivationSucceeded(generation), remoteCid);
    }

    void pollForceSetupObserved(int remoteCid) throws Exception {
        if (!PENDING_FORCE_SETUP_OBSERVED.compareAndSet(true, false)) return;
        if (runner.activePostCommitCoordinator == null) {
            log("FORCE SETUP OBSERVED: no active post-commit coordinator; override ignored.");
            return;
        }
        long generation = runner.activePostCommitCoordinator.snapshot().generation;
        log("FORCE SETUP OBSERVED: owner override — the Watch already reported gizmoDidFinishActivating on a previous live session (consumed there); replaying the evidence into this coordinator so the setup-finish chain resumes.");
        runner.executePostCommitActions(runner.activePostCommitCoordinator.onGizmoDidFinishActivating(generation), remoteCid);
    }

    void pollRedriveActivation(int remoteCid) throws Exception {
        if (!PENDING_REDRIVE_ACTIVATION.compareAndSet(true, false)) return;
        if (runner.activePostCommitCoordinator == null) {
            log("REDRIVE ACTIVATION: no active post-commit coordinator; override ignored.");
            return;
        }
        long generation = runner.activePostCommitCoordinator.snapshot().generation;
        log("REDRIVE ACTIVATION: owner override — the Watch was erased after the synthetic activation checkpoint; downgrading the durable checkpoint to IS_PAIRED_COMMITTED and re-asserting CanBeginActivation so the Watch drives ProxyActivation.");
        runner.activationPermitResendCount = 0;
        runner.executePostCommitActions(runner.activePostCommitCoordinator.onActivationRedriveRequested(generation), remoteCid);
    }

    void pollRetryActivation(int remoteCid) throws Exception {
        if (!PENDING_RETRY_ACTIVATION.compareAndSet(true, false)) return;
        if (runner.activePostCommitCoordinator == null) {
            log("RETRY ACTIVATION: no active post-commit coordinator; override ignored.");
            return;
        }
        long generation = runner.activePostCommitCoordinator.snapshot().generation;
        log("RETRY ACTIVATION: owner override — sending PBBridge RetryActivation (message 15); the Watch runs _cleanup, returns to Idle and re-starts activation unconditionally.");
        runner.activationPermitResendCount = 0;
        runner.executePostCommitActions(runner.activePostCommitCoordinator.onActivationRetryRequested(generation), remoteCid);
    }

    /**
     * Applies the outcome of the owner-credentials Activation Lock
     * challenge. A resolved record is injected exactly where the Albert
     * worker result would have landed; failures and cancellations take
     * the retryable rejection path so the Watch may re-request.
     */
    void pollActivationChallenge(int remoteCid) throws Exception {
        ActivationChallengeManager.Resolution resolution =
                ActivationChallengeManager.pollResolution();
        if (resolution == null) return;
        try {
            if (runner.activePostCommitCoordinator == null
                    || runner.activePostCommitCoordinator.snapshot().generation != resolution.generation) {
                return;
            }
            if (resolution.isSuccess()) {
                byte[] deliveryBody = resolution.body;
                try {
                    ActivationResponseCanonicalizer.Result transformed =
                            ActivationResponseCanonicalizer.maybeTransform(
                                    resolution.body);
                    if (transformed.changed()) {
                        log("ACTIVATION BODY CANONICALIZED: variant="
                                + transformed.variant()
                                + " in=" + transformed.inputLength()
                                + " out=" + transformed.body().length + ".");
                        captureActivationArtifact(
                                "activation-delivered-body",
                                transformed.body());
                        deliveryBody = transformed.body();
                    } else {
                        log("ACTIVATION CANONICALIZE SKIPPED: "
                                + transformed.note());
                    }
                } catch (Exception failure) {
                    log("ACTIVATION CANONICALIZE FAILED: "
                            + safeMessage(failure)
                            + "; delivering raw body.");
                }
                if (runner.pendingActivationResponse != null) runner.pendingActivationResponse.destroy();
                runner.pendingActivationResponse =
                        MobileActivationHttpProxy.proxyResponseOf(
                                resolution.statusCode,
                                deliveryBody,
                                resolution.archivedHeaders);
                log("ACTIVATION CHALLENGE RESOLVED: status=" + resolution.statusCode
                        + " bodyBytes=" + resolution.body.length
                        + "; forwarding the activation record to the Watch.");
                captureActivationArtifact(
                        "activation-resolved-body",
                        resolution.body);
                captureActivationArtifact(
                        "activation-resolved-headers",
                        resolution.archivedHeaders);
                runner.executePostCommitActions(runner.activePostCommitCoordinator.onActivationProxyResult(
                        resolution.generation, resolution.attempt,
                        MobileActivationHttpProxy.RequestKind.ACTIVATION, true), remoteCid);
            } else {
                log("ACTIVATION CHALLENGE UNRESOLVED: "
                        + (resolution.cancelled ? "owner cancelled" : safeMessage(new Exception(resolution.failure)))
                        + "; Watch may re-request activation.");
                runner.executePostCommitActions(runner.activePostCommitCoordinator.onActivationProxyResult(
                        resolution.generation, resolution.attempt,
                        MobileActivationHttpProxy.RequestKind.ACTIVATION, false), remoteCid);
            }
        } finally {
            resolution.destroy();
        }
    }

}

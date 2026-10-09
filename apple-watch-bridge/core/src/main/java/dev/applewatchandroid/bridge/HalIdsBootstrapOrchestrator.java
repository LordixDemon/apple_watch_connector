package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Orchestrates the progressive startup of IDS channels (Control, Class-D, Class-C)
 * over a ready normal link.
 */
final class HalIdsBootstrapOrchestrator implements AutoCloseable {
    private final IdsBootstrapState bootstrapState = new IdsBootstrapState();
    private final Consumer<String> logger;
    private boolean closed;

    HalIdsBootstrapOrchestrator(Consumer<String> logger) {
        this.logger = logger != null ? logger : (s -> {});
    }

    synchronized IdsBootstrapState.Action begin() {
        requireOpen();
        return bootstrapState.begin();
    }

    synchronized IdsBootstrapState.Action beginPaired() {
        requireOpen();
        return bootstrapState.beginPaired();
    }

    synchronized IdsBootstrapState.Action observe(
            IdsModernSessionCoordinator.EventType type,
            String service) {
        requireOpen();
        return bootstrapState.observe(type, service);
    }

    synchronized NormalLinkIdsSessionBridge.Output executeAction(
            IdsBootstrapState.Action action,
            NormalLinkIdsSessionBridge bridge) throws HostException {
        requireOpen();
        if (action == null || action == IdsBootstrapState.Action.NONE) {
            return null;
        }
        if (bridge == null) {
            throw new HostException("IDS bridge disappeared during bootstrap");
        }
        switch (action) {
            case START_CONTROL -> {
                logger.accept("IDS BOOTSTRAP: starting Class-D control service connector.");
                return bridge.startControl();
            }
            case START_KEY_PROBE -> {
                logger.accept(
                        "IDS BOOTSTRAP: Class-D/C NoOp key probes on 61314/61315 for paired control recovery.");
                return bridge.startPairedClassDKeyProbe();
            }
            case START_PAIRED_CLASS_D -> {
                logger.accept("IDS BOOTSTRAP: starting paired Default-D after key probe.");
                return bridge.startOutgoingService(
                        IdsServiceConnectorName.localDelivery(
                                IdsUtunConnectionName.defaultPaired(
                                        IdsUtunConnectionName.PRIORITY_DEFAULT,
                                        IdsUtunConnectionName.PROTECTION_CLASS_D)));
            }
            case START_CLASS_D -> {
                logger.accept("IDS BOOTSTRAP: starting NanoRegistry Class-D Setup lane.");
                return bridge.startOutgoingService(
                        IdsServiceConnectorName.localDelivery(
                                IdsUtunConnectionName.defaultPaired(
                                        IdsUtunConnectionName.PRIORITY_URGENT,
                                        IdsUtunConnectionName.PROTECTION_CLASS_D)));
            }
            case START_CLASS_C -> {
                logger.accept("IDS BOOTSTRAP: Class-D joined; starting NanoRegistry Class-C Setup lane.");
                return bridge.startOutgoingService(
                        IdsServiceConnectorName.localDelivery(
                                IdsUtunConnectionName.defaultPaired(
                                        IdsUtunConnectionName.PRIORITY_URGENT,
                                        IdsUtunConnectionName.PROTECTION_CLASS_C)));
            }
            case READY -> throw new HostException(
                    "IDS READY must activate initial setup adapter inside the event batch");
            default -> throw new HostException("Unsupported IDS bootstrap action");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("HalIdsBootstrapOrchestrator is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            bootstrapState.close();
        }
    }
}

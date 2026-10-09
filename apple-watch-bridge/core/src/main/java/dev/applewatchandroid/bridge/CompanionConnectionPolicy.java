package dev.applewatchandroid.bridge;

/** Replacement requires an explicit optical request bound to the saved identity;
 * setup commands cannot enter an operational session. */
final class CompanionConnectionPolicy {
    enum Decision { START_OPERATIONAL, START_SETUP, FORWARD_SETUP, STOP_SESSION,
        PERMISSION_REQUIRED, UNAVAILABLE, BUSY, REJECTED }
    record State(boolean permission, boolean identityKnown, boolean hasPair, boolean activated,
            boolean operationalEligible, boolean matchingPair, boolean operationalOwner, boolean setupOwner) {}
    static Decision decide(String method, State state) {
        if ("finishSetup".equals(method)) return Decision.REJECTED;
        if ("disconnectWatch".equals(method)) {
            return state.operationalOwner || state.setupOwner ? Decision.STOP_SESSION : Decision.REJECTED;
        }
        if (!state.permission) return Decision.PERMISSION_REQUIRED;
        if (java.util.Set.of("submitPin", "selectDiscoveredWatch", "activationResponse").contains(method)) {
            return state.setupOwner && !state.operationalOwner ? Decision.FORWARD_SETUP : Decision.REJECTED;
        }
        if (!state.identityKnown) return Decision.UNAVAILABLE;
        if ("confirmSetup".equals(method) && state.setupOwner && !state.operationalOwner) {
            return state.hasPair && state.activated && state.matchingPair
                    ? Decision.FORWARD_SETUP : Decision.REJECTED;
        }
        if (state.operationalOwner || state.setupOwner) return Decision.BUSY;
        return switch (method) {
            case "connectWatch" -> state.hasPair && state.operationalEligible && state.matchingPair
                    ? Decision.START_OPERATIONAL : Decision.REJECTED;
            case "beginPairing" -> !state.hasPair ? Decision.START_SETUP : Decision.REJECTED;
            case "beginOpticalPairing" -> !state.hasPair ? Decision.START_SETUP : Decision.REJECTED;
            case "replacePairOptically" -> state.hasPair && state.matchingPair
                    ? Decision.START_SETUP : Decision.REJECTED;
            case "resumeSetup" -> state.hasPair && !state.operationalEligible && state.matchingPair
                    ? Decision.START_SETUP : Decision.REJECTED;
            case "confirmSetup" -> state.hasPair && state.activated && state.matchingPair
                    ? Decision.START_SETUP : Decision.REJECTED;
            case "auditStockBond", "importStockBond", "alignStockIdentity", "probeStockReconnect"
                    -> state.hasPair && state.matchingPair ? Decision.START_SETUP : Decision.REJECTED;
            default -> Decision.REJECTED;
        };
    }
}

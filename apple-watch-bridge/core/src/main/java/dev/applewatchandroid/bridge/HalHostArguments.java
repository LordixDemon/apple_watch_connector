package dev.applewatchandroid.bridge;

import java.util.UUID;

/** Shared invocation contract for the direct host and the CarPlay lease. */
final class HalHostArguments {
    static final String TARGET = "CPH2653";
    final boolean commit;
    final boolean recovery;
    final boolean opticalPairing;
    final String operationalPairing;

    private HalHostArguments(boolean commit, boolean recovery, String operationalPairing, boolean opticalPairing) {
        this.commit = commit;
        this.recovery = recovery;
        this.operationalPairing = operationalPairing;
        this.opticalPairing = opticalPairing;
    }

    static HalHostArguments parse(String[] args) {
        if (args == null || args.length < 2 || args.length > 6
                || !"--acknowledge-target".equals(args[0]) || !TARGET.equals(args[1])) {
            throw new IllegalArgumentException("Exact target acknowledgement is missing");
        }
        boolean commit = false;
        boolean recovery = false;
        boolean optical = false;
        String operational = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--optical-pairing" -> {
                    if (optical) throw new IllegalArgumentException("Duplicate optical option");
                    optical = true;
                }
                case "--commit-is-paired" -> {
                    if (commit) throw new IllegalArgumentException("Duplicate commit option");
                    commit = true;
                }
                case "--recover-stale-smp" -> {
                    if (recovery) throw new IllegalArgumentException("Duplicate recovery option");
                    recovery = true;
                }
                case "--operational-pairing" -> {
                    if (operational != null || ++i >= args.length) {
                        throw new IllegalArgumentException("Operational pairing requires one UUID");
                    }
                    operational = canonicalUuid(args[i]);
                }
                default -> throw new IllegalArgumentException("Unsupported host option");
            }
        }
        if (operational != null && (commit || recovery || optical)) {
            throw new IllegalArgumentException("Operational mode cannot run setup or SMP recovery");
        }
        if (optical && recovery) throw new IllegalArgumentException("Optical pairing cannot run SMP recovery");
        return new HalHostArguments(commit, recovery, operational, optical);
    }

    static String canonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            throw new IllegalArgumentException("Pairing UUID must be canonical");
        }
        String canonical = UUID.fromString(value).toString();
        if (!canonical.equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Pairing UUID must be canonical");
        }
        return canonical;
    }
}

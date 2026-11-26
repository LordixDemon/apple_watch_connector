package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.spi.StrategyPolicy;

import java.util.UUID;

/** Shared invocation contract for the direct host and the CarPlay lease. */
final class HalHostArguments {
    static final String TARGET = "CPH2653";
    private static final String STRATEGIES_PREFIX = "--strategies=";

    final boolean commit;
    final boolean recovery;
    final boolean opticalPairing;
    final boolean experimentalLegacyPairing;
    final StrategyPolicy strategyPolicy;
    final String operationalPairing;

    private HalHostArguments(
            boolean commit,
            boolean recovery,
            String operationalPairing,
            boolean opticalPairing,
            StrategyPolicy strategyPolicy) {
        this.commit = commit;
        this.recovery = recovery;
        this.operationalPairing = operationalPairing;
        this.opticalPairing = opticalPairing;
        this.strategyPolicy = strategyPolicy;
        this.experimentalLegacyPairing = strategyPolicy.includesExperimental();
    }

    static HalHostArguments parse(String[] args) {
        if (args == null || args.length < 2 || args.length > 8
                || !"--acknowledge-target".equals(args[0]) || !TARGET.equals(args[1])) {
            throw new IllegalArgumentException("Exact target acknowledgement is missing");
        }
        boolean commit = false;
        boolean recovery = false;
        boolean optical = false;
        boolean experimentalLegacy = false;
        StrategyPolicy explicitStrategies = null;
        String operational = null;
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg != null && arg.startsWith(STRATEGIES_PREFIX)) {
                if (explicitStrategies != null) {
                    throw new IllegalArgumentException("Duplicate strategies option");
                }
                explicitStrategies = StrategyPolicy.parseCsv(arg.substring(STRATEGIES_PREFIX.length()));
                continue;
            }
            switch (arg) {
                case "--optical-pairing" -> {
                    if (optical) throw new IllegalArgumentException("Duplicate optical option");
                    optical = true;
                }
                case "--experimental-legacy-pairing" -> {
                    if (experimentalLegacy) {
                        throw new IllegalArgumentException("Duplicate experimental legacy pairing option");
                    }
                    experimentalLegacy = true;
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
        StrategyPolicy resolvedPolicy = explicitStrategies != null
                ? explicitStrategies
                : (experimentalLegacy ? StrategyPolicy.ALL_WITH_EXPERIMENTAL : StrategyPolicy.PRODUCTION_DEFAULT);
        boolean hasLegacy = resolvedPolicy.includesExperimental();
        if (operational != null && (commit || recovery || optical || hasLegacy || explicitStrategies != null)) {
            throw new IllegalArgumentException("Operational mode cannot run setup or SMP recovery");
        }
        if (recovery && (optical || hasLegacy || explicitStrategies != null)) {
            throw new IllegalArgumentException("SMP recovery cannot combine with optical or legacy pairing");
        }
        return new HalHostArguments(commit, recovery, operational, optical, resolvedPolicy);
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

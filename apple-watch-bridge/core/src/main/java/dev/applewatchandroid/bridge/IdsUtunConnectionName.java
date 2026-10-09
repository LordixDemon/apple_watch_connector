package dev.applewatchandroid.bridge;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic IDSUTunConnection identifiers used as the third component of
 * a local-delivery service-connector name.
 *
 * <p>For the default paired device, watchOS 26.2 seeds IDSUTunPeer with
 * {@code UTunDelivery-Default}. IDSUTunPeer then appends a priority suffix,
 * an optional {@code Cloud} marker, the data-protection suffix, and an
 * optional identifier such as {@code Relay}. These are identifiers, not
 * NanoRegistry service topics.</p>
 */
final class IdsUtunConnectionName {
    static final String DEFAULT_PAIRED_BASE =
            "UTunDelivery-Default";

    static final int PRIORITY_SYNC = 100;
    static final int PRIORITY_DEFAULT = 200;
    static final int PRIORITY_URGENT = 300;

    static final int PROTECTION_CLASS_C = 0;
    static final int PROTECTION_CLASS_C_ALTERNATE = 1;
    static final int PROTECTION_CLASS_D = 2;
    static final int PROTECTION_CLASS_UNSUFFIXED = 3;

    static final String RELAY_IDENTIFIER =
            "Relay";

    private static final Set<String>
            DEFAULT_PAIRED_IPSEC_IDENTIFIERS =
            Set.of(
                    normal(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_URGENT,
                            PROTECTION_CLASS_C,
                            null),
                    normal(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_URGENT,
                            PROTECTION_CLASS_D,
                            null),
                    normal(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_URGENT,
                            PROTECTION_CLASS_D,
                            RELAY_IDENTIFIER),
                    normal(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_DEFAULT,
                            PROTECTION_CLASS_C,
                            null),
                    normal(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_DEFAULT,
                            PROTECTION_CLASS_D,
                            null),
                    normal(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_SYNC,
                            PROTECTION_CLASS_C,
                            null),
                    normal(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_SYNC,
                            PROTECTION_CLASS_D,
                            null));

    private static final Set<String>
            DEFAULT_PAIRED_CLOUD_IDENTIFIERS =
            Set.of(
                    cloud(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_URGENT,
                            PROTECTION_CLASS_C),
                    cloud(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_URGENT,
                            PROTECTION_CLASS_D),
                    cloud(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_DEFAULT,
                            PROTECTION_CLASS_C),
                    cloud(
                            DEFAULT_PAIRED_BASE,
                            PRIORITY_DEFAULT,
                            PROTECTION_CLASS_D));

    private IdsUtunConnectionName() {
    }

    static String defaultPaired(
            int priority,
            int protectionClass) {
        return normal(
                DEFAULT_PAIRED_BASE,
                priority,
                protectionClass,
                null);
    }

    static String defaultPairedRelay() {
        return normal(
                DEFAULT_PAIRED_BASE,
                PRIORITY_URGENT,
                PROTECTION_CLASS_D,
                RELAY_IDENTIFIER);
    }

    static String defaultPairedCloud(
            int priority,
            int protectionClass) {
        return cloud(
                DEFAULT_PAIRED_BASE,
                priority,
                protectionClass);
    }

    static boolean isDefaultPairedIpsecIdentifier(
            String identifier) {
        return DEFAULT_PAIRED_IPSEC_IDENTIFIERS.contains(
                identifier);
    }

    static boolean isDefaultPairedCloudIdentifier(
            String identifier) {
        return DEFAULT_PAIRED_CLOUD_IDENTIFIERS.contains(
                identifier);
    }

    /**
     * Class-C vs Class-D from a connector encode, a bare UTun name, or the
     * {@code nano-class-*} aliases.
     *
     * <p>{@code UTunDelivery-Default-Default-C} contains the substring
     * {@code default-d} (the second {@code Default}). Matching must use the
     * protection suffix of the last path component, never {@code contains}.</p>
     */
    static boolean isClassCIdentifier(String service) {
        return protectionSuffixOf(service) == PROTECTION_CLASS_C;
    }

    static boolean isClassDIdentifier(String service) {
        return protectionSuffixOf(service) == PROTECTION_CLASS_D;
    }

    static boolean isConcreteUtunName(String service) {
        if (service == null) {
            return false;
        }
        return lastComponent(service).startsWith(DEFAULT_PAIRED_BASE);
    }

    static String lastComponent(String service) {
        if (service == null) {
            return "";
        }
        int slash = service.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < service.length()) {
            return service.substring(slash + 1);
        }
        return service;
    }

    private static int protectionSuffixOf(String service) {
        if (service == null || service.isEmpty()) {
            return PROTECTION_CLASS_UNSUFFIXED;
        }
        if ("nano-class-c".equals(service)) {
            return PROTECTION_CLASS_C;
        }
        if ("nano-class-d".equals(service)) {
            return PROTECTION_CLASS_D;
        }
        String lower = lastComponent(service).toLowerCase(Locale.ROOT);
        if (lower.endsWith("-relay")) {
            lower = lower.substring(0, lower.length() - "-relay".length());
        }
        if (lower.endsWith("-c")) {
            return PROTECTION_CLASS_C;
        }
        if (lower.endsWith("-d")) {
            return PROTECTION_CLASS_D;
        }
        return PROTECTION_CLASS_UNSUFFIXED;
    }

    private static String normal(
            String base,
            int priority,
            int protectionClass,
            String optionalIdentifier) {
        String identifier =
                requireBase(
                        base)
                        + prioritySuffix(
                                priority)
                        + protectionSuffix(
                                protectionClass);
        if (optionalIdentifier == null) {
            return identifier;
        }
        return identifier
                + "-"
                + requireOptionalIdentifier(
                        optionalIdentifier);
    }

    private static String cloud(
            String base,
            int priority,
            int protectionClass) {
        if (priority != PRIORITY_URGENT
                && priority != PRIORITY_DEFAULT) {
            throw new IllegalArgumentException(
                    "IDS cloud UTun does not support this priority");
        }
        return requireBase(
                base)
                + prioritySuffix(
                        priority)
                + "Cloud"
                + protectionSuffix(
                        protectionClass);
    }

    private static String prioritySuffix(
            int priority) {
        return switch (priority) {
            case PRIORITY_SYNC -> "-Sync";
            case PRIORITY_DEFAULT -> "-Default";
            case PRIORITY_URGENT -> "-Urgent";
            default -> throw new IllegalArgumentException(
                    "Unsupported IDS socket priority");
        };
    }

    private static String protectionSuffix(
            int protectionClass) {
        return switch (protectionClass) {
            case PROTECTION_CLASS_C,
                    PROTECTION_CLASS_C_ALTERNATE -> "-C";
            case PROTECTION_CLASS_D -> "-D";
            case PROTECTION_CLASS_UNSUFFIXED -> "";
            default -> throw new IllegalArgumentException(
                    "Unsupported IDS data-protection class");
        };
    }

    private static String requireBase(
            String base) {
        if (base == null
                || base.isEmpty()
                || base.indexOf(
                        '/') >= 0
                || base.indexOf(
                        '\0') >= 0) {
            throw new IllegalArgumentException(
                    "IDS UTun base identifier is invalid");
        }
        return base;
    }

    private static String requireOptionalIdentifier(
            String identifier) {
        if (identifier.isEmpty()
                || identifier.indexOf(
                        '/') >= 0
                || identifier.indexOf(
                        '\0') >= 0) {
            throw new IllegalArgumentException(
                    "IDS UTun optional identifier is invalid");
        }
        return identifier;
    }
}

package dev.applewatchandroid.bridge;

import java.util.*;

/** Compares data only. Never instantiates an archived Objective-C class or runs an intent. */
final class NativeFaceConfigurationEquality {
    private NativeFaceConfigurationEquality() { }

    static boolean same(Object expected, Object observed) {
        if (expected instanceof Map<?, ?> a && observed instanceof Map<?, ?> b) {
            if (!a.keySet().equals(b.keySet())) return false;
            for (Object key : a.keySet()) {
                Object x = a.get(key), y = b.get(key);
                if (Objects.equals(x, y)) continue;
                if ("descriptor".equals(key) && x instanceof Map<?, ?> dx && y instanceof Map<?, ?> dy) {
                    if (!dx.keySet().equals(dy.keySet())) return false;
                    for (Object field : dx.keySet()) {
                        if ("intent".equals(field)) {
                            if (!intent(dx.get(field), dy.get(field))) return false;
                        } else if (!same(dx.get(field), dy.get(field))) return false;
                    }
                } else if (!same(x, y)) return false;
            }
            return true;
        }
        if (expected instanceof List<?> a && observed instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
            return true;
        }
        return Objects.equals(expected, observed);
    }

    private static boolean intent(Object a, Object b) {
        if (Objects.equals(a, b)) return true;
        if (!(a instanceof String x) || !(b instanceof String y) || x.length() > 131072 || y.length() > 131072) return false;
        try {
            return new Comparison().archive(Base64.getDecoder().decode(x), Base64.getDecoder().decode(y), 0);
        } catch (IllegalArgumentException malformed) { return false; }
    }

    private static final class Comparison {
        int visits;
        boolean archive(byte[] a, byte[] b, int depth) {
            Object x = AppleBinaryPropertyList.decodeIntentData(a);
            Object y = AppleBinaryPropertyList.decodeIntentData(b);
            if (!(x instanceof Map<?, ?> left) || !(y instanceof Map<?, ?> right)
                    || !left.keySet().equals(Set.of("$archiver", "$version", "$objects", "$top"))
                    || !right.keySet().equals(left.keySet())
                    || !"NSKeyedArchiver".equals(left.get("$archiver"))
                    || !Objects.equals(left.get("$archiver"), right.get("$archiver"))
                    || !Long.valueOf(100000).equals(left.get("$version"))
                    || !Objects.equals(left.get("$version"), right.get("$version"))
                    || !(left.get("$objects") instanceof List<?> lo) || lo.isEmpty()
                    || !(right.get("$objects") instanceof List<?> ro) || ro.isEmpty()
                    || !"$null".equals(lo.get(0)) || !"$null".equals(ro.get(0))) return false;
            return value(left.get("$top"), right.get("$top"), lo, ro, new HashSet<>(), depth + 1);
        }
        boolean value(Object a, Object b, List<?> lo, List<?> ro, Set<Long> seen, int depth) {
            if (++visits > 32768 || depth > 64) throw new IllegalArgumentException("Intent comparison bound exceeded");
            if (a instanceof AppleBinaryPropertyList.Uid x && b instanceof AppleBinaryPropertyList.Uid y) {
                if (x.value() >= lo.size() || y.value() >= ro.size()) return false;
                if (!seen.add(((long) x.value() << 32) | y.value())) return true;
                return value(lo.get(x.value()), ro.get(y.value()), lo, ro, seen, depth + 1);
            }
            if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
                // NSDictionary key order and UID allocation change during Watch re-archiving.
                if (x.containsKey("NS.keys") && y.containsKey("NS.keys")) {
                    if (!x.keySet().equals(Set.of("$class", "NS.keys", "NS.objects"))
                            || !y.keySet().equals(x.keySet())
                            || !value(x.get("$class"), y.get("$class"), lo, ro, seen, depth + 1)) return false;
                    x = dictionary(x, lo); y = dictionary(y, ro);
                }
                if (!x.keySet().equals(y.keySet())) return false;
                for (Object key : x.keySet()) if (!value(x.get(key), y.get(key), lo, ro, seen, depth + 1)) return false;
                return true;
            }
            if (a instanceof List<?> x && b instanceof List<?> y) {
                if (x.size() != y.size()) return false;
                for (int i = 0; i < x.size(); i++) if (!value(x.get(i), y.get(i), lo, ro, seen, depth + 1)) return false;
                return true;
            }
            if (a instanceof byte[] x && b instanceof byte[] y) {
                if (Arrays.equals(x, y)) return true;
                if (x.length >= 8 && y.length >= 8 && x[0] == 'b' && y[0] == 'b') return archive(x, y, depth + 1);
                return false;
            }
            return Objects.equals(a, b);
        }
        private Map<Object, Object> dictionary(Map<?, ?> map, List<?> objects) {
            if (!(map.get("NS.keys") instanceof List<?> keys) || !(map.get("NS.objects") instanceof List<?> values)
                    || keys.size() != values.size()) throw new IllegalArgumentException("Invalid intent dictionary");
            Map<Object, Object> result = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                Object key = keys.get(i);
                if (key instanceof AppleBinaryPropertyList.Uid uid) {
                    if (uid.value() >= objects.size()) throw new IllegalArgumentException("Invalid intent key UID");
                    key = objects.get(uid.value());
                }
                if (!(key instanceof String || key instanceof Number) || result.containsKey(key))
                    throw new IllegalArgumentException("Invalid intent dictionary key");
                result.put(key, values.get(i));
            }
            return result;
        }
    }
}

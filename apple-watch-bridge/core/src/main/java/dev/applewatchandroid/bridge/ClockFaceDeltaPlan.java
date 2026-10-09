package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable mutations derived from a complete committed collection, plus readback predicates. */
final class ClockFaceDeltaPlan implements AutoCloseable {
    enum Kind { SELECT, DUPLICATE, ADD, UPDATE, RESOURCES, REORDER, REMOVE }
    final Kind kind;
    final String face;
    final long baselineObservedAt;
    private final List<byte[]> changes;
    private final List<String> expectedOrder;
    private final String expectedSelected;
    private final Object expectedConfiguration;
    private Map<String, byte[]> expectedResources = Map.of();
    private boolean exactResources;
    private boolean closed;

    private ClockFaceDeltaPlan(Kind kind, String face, ClockFaceCollection baseline, List<byte[]> changes,
                              List<String> order, String selected, byte[] configuration) {
        this.kind = kind; this.face = face; baselineObservedAt = baseline.observedAt;
        this.changes = changes; expectedOrder = order == null ? null : List.copyOf(order);
        expectedSelected = selected;
        expectedConfiguration = configuration == null ? null : BoundedJson.decode(configuration, NtkFacePayloadCodec.MAX_CONFIG_BYTES);
    }

    static ClockFaceDeltaPlan select(ClockFaceCollection baseline, String face) {
        String id = known(baseline, face);
        return new ClockFaceDeltaPlan(Kind.SELECT, id, baseline,
                List.of(NtkFaceChangeEncoder.select(id)), null, id, null);
    }

    static ClockFaceDeltaPlan duplicate(ClockFaceCollection baseline, String source, String target) throws IOException {
        String from = known(baseline, source), to = NtkFacePayloadCodec.uuid(target);
        if (baseline.configurations.containsKey(to) || baseline.ordered.size() >= NtkFacePayloadCodec.MAX_FACES
                || baseline.configurations.size() >= NtkFacePayloadCodec.MAX_FACES) throw invalid();
        byte[] configuration = baseline.configurations.get(from).clone();
        List<byte[]> changes = new ArrayList<>();
        try {
            byte[] archive = baseline.nativePackage(from);
            if (archive == null) throw invalid(); // a JSON projection cannot establish that external resources are absent
            byte[] packagedConfiguration = NtkFacePayloadCodec.configurationFromZip(archive);
            try {
                if (!BoundedJson.decode(packagedConfiguration, NtkFacePayloadCodec.MAX_CONFIG_BYTES).equals(
                        BoundedJson.decode(configuration, NtkFacePayloadCodec.MAX_CONFIG_BYTES))) throw invalid();
            } finally { Arrays.fill(packagedConfiguration, (byte) 0); }
            long total = configuration.length;
            for (byte[] bytes : baseline.configurations.values()) total += bytes.length;
            if (total > 512 * 1024) throw invalid();
            List<String> order = new ArrayList<>(baseline.ordered); order.add(to);
            Map<String, byte[]> resources = NtkFacePayloadCodec.resourceDigests(archive);
            try { changes.addAll(NtkFaceChangeEncoder.addArchiveParts(to, archive)); }
            finally { Arrays.fill(archive, (byte) 0); }
            changes.add(NtkFaceChangeEncoder.order(order)); changes.add(NtkFaceChangeEncoder.select(to));
            var plan = new ClockFaceDeltaPlan(Kind.DUPLICATE, to, baseline, changes, order, to, configuration);
            plan.expectedResources = resources;
            return plan;
        } catch (IOException | RuntimeException failure) {
            for (byte[] bytes : changes) Arrays.fill(bytes, (byte) 0); throw failure;
        } finally { Arrays.fill(configuration, (byte) 0); }
    }

    static ClockFaceDeltaPlan update(ClockFaceCollection baseline, String face, byte[] configuration) throws IOException {
        String id = known(baseline, face);
        if (!NtkFacePayloadCodec.configurationIdentity(baseline.configurations.get(id))
                .equals(NtkFacePayloadCodec.configurationIdentity(configuration))) throw invalid();
        requireConfigurationBudget(baseline, id, configuration);
        byte[] archive = baseline.nativePackage(id);
        Map<String, byte[]> resources;
        try {
            if (archive == null) throw invalid(); // cannot prove that a config-only edit preserved unknown resources
            resources = NtkFacePayloadCodec.resourceDigests(archive);
        } finally { if (archive != null) Arrays.fill(archive, (byte) 0); }
        var plan = new ClockFaceDeltaPlan(Kind.UPDATE, id, baseline,
                List.of(NtkFaceChangeEncoder.update(id, configuration)), null, null, configuration);
        plan.expectedResources = resources;
        return plan;
    }

    static ClockFaceDeltaPlan add(ClockFaceCollection baseline, String target, byte[] archive) throws IOException {
        requireComplete(baseline);
        String id = NtkFacePayloadCodec.uuid(target);
        if (baseline.configurations.containsKey(id) || baseline.configurations.size() >= NtkFacePayloadCodec.MAX_FACES
                || archive == null || archive.length > NativeFacePackageStore.MAX_PACKAGE_BYTES) throw invalid();
        byte[] configuration = NtkFacePayloadCodec.configurationFromZip(archive);
        List<byte[]> changes = new ArrayList<>();
        try {
            // Compatibility is established by the Watch's committed readback, never a guessed family allowlist.
            NtkFacePayloadCodec.configurationIdentity(configuration);
            requireConfigurationBudget(baseline, id, configuration);
            List<String> order = new ArrayList<>(baseline.ordered); order.add(id);
            byte[] wire = NtkFacePayloadCodec.wireArchive(archive);
            try { changes.addAll(NtkFaceChangeEncoder.addArchiveParts(id, wire)); }
            finally { Arrays.fill(wire, (byte) 0); }
            changes.add(NtkFaceChangeEncoder.order(order)); changes.add(NtkFaceChangeEncoder.select(id));
            var plan = new ClockFaceDeltaPlan(Kind.ADD, id, baseline, changes, order, id, configuration);
            plan.expectedResources = NtkFacePayloadCodec.resourceDigests(archive);
            return plan;
        } catch (IOException | RuntimeException failure) {
            for (byte[] bytes : changes) Arrays.fill(bytes, (byte) 0); throw failure;
        } finally { Arrays.fill(configuration, (byte) 0); }
    }

    static ClockFaceDeltaPlan resources(ClockFaceCollection baseline, String face, byte[] archive, String baselineHash) throws IOException {
        String id = known(baseline, face);
        byte[] config = NtkFacePayloadCodec.configurationFromZip(archive), old = null, resources = null, merged = null;
        List<byte[]> changes = new ArrayList<>();
        try {
            // Editing images must never overwrite concurrently changed settings or complications.
            if (!NativeFaceConfigurationEquality.same(BoundedJson.decode(config, NtkFacePayloadCodec.MAX_CONFIG_BYTES),
                    BoundedJson.decode(baseline.configurations.get(id), NtkFacePayloadCodec.MAX_CONFIG_BYTES))) throw invalid();
            old = baseline.nativePackage(id);
            if (old == null || !NativeFacePackageStore.hash(old).equals(baselineHash)) throw invalid();
            resources = NtkFaceResources.fromPackage(archive);
            merged = NtkFaceResources.replacing(old, resources);
            var expected = NtkFacePayloadCodec.resourceDigests(merged);
            Arrays.fill(old, (byte) 0); old = null;
            Arrays.fill(merged, (byte) 0); merged = null;
            changes.addAll(NtkFaceChangeEncoder.resourceParts(id, resources));
            var plan = new ClockFaceDeltaPlan(Kind.RESOURCES, id, baseline, changes,
                    baseline.ordered, baseline.selected, baseline.configurations.get(id));
            plan.expectedResources = expected;
            plan.exactResources = true;
            return plan;
        } catch (IOException | RuntimeException failure) {
            for (byte[] bytes : changes) Arrays.fill(bytes, (byte) 0); throw failure;
        } finally {
            Arrays.fill(config, (byte) 0);
            if (old != null) Arrays.fill(old, (byte) 0);
            if (resources != null) Arrays.fill(resources, (byte) 0);
            if (merged != null) Arrays.fill(merged, (byte) 0);
        }
    }

    private static void requireConfigurationBudget(ClockFaceCollection baseline, String id, byte[] configuration) {
        long total = configuration.length;
        for (var entry : baseline.configurations.entrySet()) if (!entry.getKey().equals(id)) total += entry.getValue().length;
        if (total > 512 * 1024) throw invalid();
    }

    static ClockFaceDeltaPlan reorder(ClockFaceCollection baseline, List<String> order) {
        requireComplete(baseline);
        if (order == null || order.size() != baseline.ordered.size()) throw invalid();
        List<String> canonical = order.stream().map(NtkFacePayloadCodec::uuid).collect(java.util.stream.Collectors.toList());
        if (Set.copyOf(canonical).size() != canonical.size() || !Set.copyOf(canonical).equals(Set.copyOf(baseline.ordered))) throw invalid();
        return new ClockFaceDeltaPlan(Kind.REORDER, "", baseline,
                List.of(NtkFaceChangeEncoder.order(canonical)), canonical, null, null);
    }

    static ClockFaceDeltaPlan remove(ClockFaceCollection baseline, String face) {
        String id = known(baseline, face);
        if (baseline.ordered.size() < 2) throw invalid(); // never remove the final face
        List<String> order = new ArrayList<>(baseline.ordered); order.remove(id);
        String selected = baseline.selected.equals(id) ? order.get(0) : baseline.selected;
        // Select the retained face before removing the selected UUID.
        List<byte[]> changes = List.of(NtkFaceChangeEncoder.select(selected), NtkFaceChangeEncoder.remove(id),
                NtkFaceChangeEncoder.order(order));
        return new ClockFaceDeltaPlan(Kind.REMOVE, id, baseline, changes, order, selected, null);
    }

    List<byte[]> copyChanges() {
        if (closed) throw new IllegalStateException("Closed native face delta plan");
        return changes.stream().map(byte[]::clone).collect(java.util.stream.Collectors.toList());
    }

    private List<List<byte[]>> batches() {
        List<List<byte[]>> result = new ArrayList<>(); List<byte[]> batch = new ArrayList<>(); int size = 0;
        for (byte[] bytes : changes) {
            if (bytes.length > ClockFaceDeltaProtocol.MAX_BATCH_BYTES - 4096) throw invalid();
            if (batch.size() == ClockFaceDeltaProtocol.MAX_CHANGES || size + bytes.length > ClockFaceDeltaProtocol.MAX_BATCH_BYTES - 4096) {
                result.add(batch); batch = new ArrayList<>(); size = 0;
            }
            batch.add(bytes); size += bytes.length;
        }
        if (!batch.isEmpty()) result.add(batch);
        return result;
    }
    int batchCount() { return batches().size(); }
    List<byte[]> copyBatch(int index) {
        if (closed) throw new IllegalStateException("Closed native face delta plan");
        return batches().get(index).stream().map(byte[]::clone).collect(java.util.stream.Collectors.toList());
    }
    long writeTimeoutMs() {
        long bytes = 0; for (byte[] change : changes) bytes += change.length;
        return Math.min(1800000L, 90000L + (batchCount() <= 1 ? 0 : bytes / 8192 * 1000));
    }

    boolean matches(ClockFaceCollection observed) {
        if (closed || observed == null || !observed.complete() || observed.observedAt <= baselineObservedAt) return false;
        if (expectedOrder != null && !expectedOrder.equals(observed.ordered)
                || expectedSelected != null && !expectedSelected.equals(observed.selected)) return false;
        if (kind == Kind.REMOVE) return !observed.configurations.containsKey(face) && !observed.ordered.contains(face);
        if (kind == Kind.REORDER) return true;
        byte[] config = observed.configurations.get(face);
        boolean configurationMatches = config != null && observed.ordered.contains(face) && (expectedConfiguration == null
                || NativeFaceConfigurationEquality.same(expectedConfiguration,
                        BoundedJson.decode(config, NtkFacePayloadCodec.MAX_CONFIG_BYTES)));
        if (!configurationMatches || expectedResources.isEmpty()) return configurationMatches;
        byte[] archive = null;
        try {
            archive = observed.nativePackage(face);
            if (archive == null) return false;
            Map<String, byte[]> actual = NtkFacePayloadCodec.resourceDigests(archive);
            if (exactResources && !actual.keySet().equals(expectedResources.keySet())) return false;
            for (var resource : expectedResources.entrySet()) {
                if (!Arrays.equals(resource.getValue(), actual.get(resource.getKey()))) return false;
            }
            return true;
        } catch (IOException unavailable) { return false; }
        finally { if (archive != null) Arrays.fill(archive, (byte) 0); }
    }

    @Override public void close() {
        closed = true; for (byte[] bytes : changes) Arrays.fill(bytes, (byte) 0);
    }

    private static String known(ClockFaceCollection baseline, String face) {
        requireComplete(baseline); String id = NtkFacePayloadCodec.uuid(face);
        if (!baseline.ordered.contains(id) || !baseline.configurations.containsKey(id)) throw invalid();
        return id;
    }
    private static void requireComplete(ClockFaceCollection baseline) {
        if (baseline == null || !baseline.complete() || baseline.observedAt <= 0) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Native face delta requires a valid complete collection and known UUIDs"); }
}

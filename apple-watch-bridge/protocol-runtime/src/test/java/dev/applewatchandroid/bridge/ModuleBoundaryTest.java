package dev.applewatchandroid.bridge;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Architectural boundary guard enforcing clean modular dependencies across {@code spi/},
 * {@code discovery/}, {@code transport/}, {@code strategy/}, {@code strategy/s1..s4/}, and
 * {@code strategy/legacy/}.
 */
public final class ModuleBoundaryTest {

    @Test
    public void enforcesStrictModularPackageBoundaries() throws IOException {
        Path bridgeRoot = locateBridgeRoot();
        Path coreMain = bridgeRoot.resolve("core/src/main/java/dev/applewatchandroid/bridge");
        Path runtimeMain = bridgeRoot.resolve("protocol-runtime/src/main/java/dev/applewatchandroid/bridge");

        assertNoForbiddenImports(coreMain.resolve("spi"), List.of(
                ".discovery.", ".transport.", ".strategy.", "HalTransportSession"));

        assertNoForbiddenImports(coreMain.resolve("discovery"), List.of(
                ".transport.", ".strategy.", "HalTransportSession"));

        assertNoForbiddenImports(runtimeMain.resolve("transport"), List.of(
                ".strategy.", "HalTransportSession", "LegacyPairingRunner"));

        assertNoForbiddenImports(runtimeMain.resolve("strategy/s4"), List.of(
                ".strategy.s1.", ".strategy.s2.", ".strategy.s3.", ".strategy.legacy.",
                "LegacyWatchPairingCodec", "LegacyPairingRunner"));

        for (String legacyDir : List.of("strategy/s1", "strategy/s2", "strategy/s3", "strategy/legacy")) {
            assertNoForbiddenImports(runtimeMain.resolve(legacyDir), List.of(
                    ".strategy.s4.", "ModernPairingIkeRunner", "ModernIdsSessionRunner"));
        }

        Path registryFile = runtimeMain.resolve("strategy/StrategyRegistry.java");
        assertTrue("StrategyRegistry.java must exist", Files.isRegularFile(registryFile));
        String registrySrc = Files.readString(registryFile, StandardCharsets.UTF_8);
        for (String concretePkg : List.of(
                ".strategy.s1.", ".strategy.s2.", ".strategy.s3.", ".strategy.s4.", ".strategy.legacy.")) {
            assertFalse("StrategyRegistry must remain decoupled from concrete plugin " + concretePkg,
                    registrySrc.contains(concretePkg));
        }
    }

    private static void assertNoForbiddenImports(Path dir, List<String> forbiddenTokens) throws IOException {
        assertTrue("Expected modular package directory to exist: " + dir, Files.isDirectory(dir));
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(javaFiles::add);
        }
        assertFalse("Expected at least one Java file in " + dir, javaFiles.isEmpty());
        for (Path file : javaFiles) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String forbidden : forbiddenTokens) {
                    assertFalse(
                            file.getFileName() + ":" + (i + 1) + " violates modular boundary by importing "
                                    + forbidden + " (" + trimmed + ")",
                            trimmed.contains(forbidden));
                }
            }
        }
    }

    private static Path locateBridgeRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && cur != null; depth++) {
            if (Files.isDirectory(cur.resolve("core/src/main/java"))
                    && Files.isDirectory(cur.resolve("protocol-runtime/src/main/java"))) {
                return cur;
            }
            Path nested = cur.resolve("apple-watch-bridge");
            if (Files.isDirectory(nested.resolve("core/src/main/java"))
                    && Files.isDirectory(nested.resolve("protocol-runtime/src/main/java"))) {
                return nested;
            }
            cur = cur.getParent();
        }
        throw new AssertionError("Could not locate apple-watch-bridge root from " + Path.of("").toAbsolutePath());
    }
}

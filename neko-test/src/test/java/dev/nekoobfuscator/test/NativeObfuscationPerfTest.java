package dev.nekoobfuscator.test;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeObfuscationPerfTest {
    @BeforeAll
    static void prepareFixtures() throws Exception {
        NativeObfuscationHelper.ensureObfuscatedFixtures();
    }

    @Test
    @Timeout(2)
    void nativeObfuscation_TEST_obfuscationSpeedUnder60s() throws Exception {
        NativeObfuscationHelper.ObfuscationRunResult result = NativeObfuscationHelper.obfuscateJar(
            NativeObfuscationHelper.jarsDir().resolve("TEST.jar"),
            NativeObfuscationHelper.nativeWorkDir().resolve("perf-TEST-native.jar"),
            NativeObfuscationHelper.configsDir().resolve("native-test.yml"),
            Duration.ofMinutes(2)
        );

        assertTrue(result.duration().toSeconds() < 60, () -> "TEST obfuscation exceeded 60s: " + result.duration() + "\n" + result.combinedOutput());
    }

    @Test
    @Timeout(3)
    void nativeObfuscation_obfusjack_obfuscationSpeedUnder120s() throws Exception {
        NativeObfuscationHelper.ObfuscationRunResult result = NativeObfuscationHelper.obfuscateJar(
            NativeObfuscationHelper.jarsDir().resolve("obfusjack-test21.jar"),
            NativeObfuscationHelper.nativeWorkDir().resolve("perf-obfusjack-native.jar"),
            NativeObfuscationHelper.configsDir().resolve("native-obfusjack.yml"),
            Duration.ofMinutes(3)
        );

        assertTrue(result.duration().toSeconds() < 120, () -> "obfusjack obfuscation exceeded 120s: " + result.duration() + "\n" + result.combinedOutput());
    }

    @Test
    @Timeout(2)
    void nativeObfuscation_SnakeGame_obfuscationSpeedUnder30s() throws Exception {
        NativeObfuscationHelper.ObfuscationRunResult result = NativeObfuscationHelper.obfuscateJar(
            NativeObfuscationHelper.jarsDir().resolve("SnakeGame.jar"),
            NativeObfuscationHelper.nativeWorkDir().resolve("perf-SnakeGame-native.jar"),
            NativeObfuscationHelper.configsDir().resolve("native-snake.yml"),
            Duration.ofMinutes(2)
        );

        assertTrue(result.duration().toSeconds() < 30, () -> "SnakeGame obfuscation exceeded 30s: " + result.duration() + "\n" + result.combinedOutput());
    }

    @Test
    void nativeObfuscation_TEST_sharedLibrarySizeWithinSanityBounds() throws Exception {
        String libEntry = NativeObfuscationHelper.platformLibraryEntryName();
        byte[] libraryBytes = NativeObfuscationHelper.extractEntry(NativeObfuscationHelper.artifact("TEST").outputJar(), libEntry);

        assertTrue(libraryBytes.length >= 50 * 1024, () -> "Expected native library to be at least 50KB but was " + libraryBytes.length + " bytes");
        assertTrue(libraryBytes.length <= 5 * 1024 * 1024, () -> "Expected native library to be at most 5MB but was " + libraryBytes.length + " bytes");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void nativeObfuscation_TEST_calcBenchmarkMedianUnder150ms() throws Exception {
        Path jar = NativeObfuscationHelper.artifact("TEST").outputJar();
        List<Long> measurements = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            Path stdout = NativeObfuscationHelper.nativeWorkDir().resolve("calc-bench-" + i + ".stdout.log");
            Path stderr = NativeObfuscationHelper.nativeWorkDir().resolve("calc-bench-" + i + ".stderr.log");
            NativeObfuscationHelper.JarRunResult run = NativeObfuscationHelper.runJar(jar, List.of(), stdout, stderr, Duration.ofMinutes(2));
            assertEquals(0, run.exitCode(), () -> run.combinedOutput());
            NativeObfuscationHelper.assertNoFatalNativeCrash(run);
            measurements.add(NativeObfuscationHelper.parseCalcMillis(run.combinedOutput()));
        }

        List<Long> steadyState = new ArrayList<>(measurements.subList(2, measurements.size()));
        Collections.sort(steadyState);
        long median = steadyState.get(steadyState.size() / 2);

        assertTrue(median < 150, () -> "Expected median Calc time < 150ms but got " + median + "ms from measurements " + measurements);
    }
}

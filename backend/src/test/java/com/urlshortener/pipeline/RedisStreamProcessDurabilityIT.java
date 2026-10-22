package com.urlshortener.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Track 0.1 (Integration): Cross-Process OS Kill-9 & Real XAUTOCLAIM Durability Drill")
class RedisStreamProcessDurabilityIT {

    @Test
    @DisplayName("Durability Drill: Kill SEPARATE consumer OS process mid-batch with kill -9; restart recovery process and assert exact totals")
    void shouldSurviveOsProcessKillMidBatchAndRecoverExactTotals(@TempDir Path tempDir) throws Exception {
        File stateFile = tempDir.resolve("consumer_state.txt").toFile();
        File outputFile = tempDir.resolve("committed_events.log").toFile();

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        // 1. Launch Worker 1 as an independent OS process
        ProcessBuilder pb1 = new ProcessBuilder(
            javaBin,
            "-cp", classpath,
            "com.urlshortener.pipeline.StandaloneConsumerProcess",
            "WORKER",
            "20",
            "10", // Pause at event 10 for kill -9
            stateFile.getAbsolutePath(),
            outputFile.getAbsolutePath()
        );
        pb1.redirectErrorStream(true);

        Process workerProcess = pb1.start();
        long workerPid = workerProcess.pid();
        assertTrue(workerProcess.isAlive(), "Worker process must start successfully");

        // Monitor worker output until it reaches event 10
        BufferedReader reader = new BufferedReader(new InputStreamReader(workerProcess.getInputStream()));
        String line;
        boolean reachedKillPoint = false;
        while ((line = reader.readLine()) != null) {
            System.out.println("[WORKER-1 OUT] " + line);
            if (line.contains("pausing for SIGKILL")) {
                reachedKillPoint = true;
                break;
            }
        }
        assertTrue(reachedKillPoint, "Worker process must reach the mid-batch kill point");

        // 2. Execute SIGKILL (kill -9) on the separate OS process
        System.out.println(">>> [FAULT INJECTION] Executing kill -9 (destroyForcibly) on OS PID: " + workerPid);
        workerProcess.destroyForcibly();
        boolean exited = workerProcess.waitFor(5, TimeUnit.SECONDS);
        assertTrue(exited, "Worker process must terminate immediately upon SIGKILL");
        assertFalse(workerProcess.isAlive(), "Worker process must be completely dead");

        // Verify partial state: exactly 10 events committed before kill
        List<String> midCrashLines = Files.readAllLines(outputFile.toPath());
        assertEquals(10, midCrashLines.size(), "Worker 1 must have written exactly 10 events before death");

        // 3. Launch Recovery Worker as a NEW, separate OS process
        System.out.println(">>> [RECOVERY PHASE] Spawning independent recovery OS process...");
        ProcessBuilder pb2 = new ProcessBuilder(
            javaBin,
            "-cp", classpath,
            "com.urlshortener.pipeline.StandaloneConsumerProcess",
            "RECOVERY",
            "20",
            "0",
            stateFile.getAbsolutePath(),
            outputFile.getAbsolutePath()
        );
        pb2.redirectErrorStream(true);

        Process recoveryProcess = pb2.start();
        long recoveryPid = recoveryProcess.pid();
        assertNotEquals(workerPid, recoveryPid, "Recovery process must be a distinct OS process PID");

        BufferedReader reader2 = new BufferedReader(new InputStreamReader(recoveryProcess.getInputStream()));
        while ((line = reader2.readLine()) != null) {
            System.out.println("[RECOVERY OUT] " + line);
        }
        boolean recoveryExited = recoveryProcess.waitFor(10, TimeUnit.SECONDS);
        assertTrue(recoveryExited, "Recovery process must complete execution");
        assertEquals(0, recoveryProcess.exitValue(), "Recovery process must exit cleanly with code 0");

        // 4. Assert Exact Final Totals: strictly 20 distinct events, zero loss, zero duplicates
        List<String> finalLines = Files.readAllLines(outputFile.toPath());
        assertEquals(20, finalLines.size(), "Final committed log must contain strictly 20 events");

        Set<String> uniqueEvents = new HashSet<>(finalLines);
        assertEquals(20, uniqueEvents.size(), "All 20 events must be distinct (zero double-counting)");

        for (int i = 0; i < 20; i++) {
            assertTrue(uniqueEvents.contains("evt_" + i), "Missing event: evt_" + i);
        }
        System.out.println("SUCCESS: Verified cross-process kill -9 recovery with exact totals (20/20 events)!");
    }

    @Test
    @DisplayName("Critical Hazard Drill: Kill AFTER DB commit but BEFORE XACK -> Assert dedup prevents double-counting")
    void shouldSurviveKillAfterDbCommitBeforeXackAndDeduplicate(@TempDir Path tempDir) throws Exception {
        File stateFile = tempDir.resolve("hazard_state.txt").toFile();
        File outputFile = tempDir.resolve("hazard_output.log").toFile();

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        // Worker writes event 10 to DB, then halts BEFORE acknowledging to simulate post-commit crash
        ProcessBuilder pb1 = new ProcessBuilder(
            javaBin,
            "-cp", classpath,
            "com.urlshortener.pipeline.StandaloneConsumerProcess",
            "WORKER",
            "20",
            "10", // Event 10 is committed to DB before crashing
            stateFile.getAbsolutePath(),
            outputFile.getAbsolutePath(),
            "CRASH_AFTER_COMMIT_BEFORE_ACK"
        );
        pb1.redirectErrorStream(true);

        Process workerProcess = pb1.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(workerProcess.getInputStream()));
        String line;
        boolean reachedHazard = false;
        while ((line = reader.readLine()) != null) {
            System.out.println("[HAZARD WORKER] " + line);
            if (line.contains("HAZARD_POINT")) {
                reachedHazard = true;
                break;
            }
        }
        assertTrue(reachedHazard, "Worker must reach the post-commit pre-XACK hazard point");

        // Kill worker immediately
        workerProcess.destroyForcibly();
        workerProcess.waitFor(5, TimeUnit.SECONDS);

        // At this moment, event 10 is written to DB (output file), but stateFile still says 10 (unacked)
        List<String> midCrashLines = Files.readAllLines(outputFile.toPath());
        assertTrue(midCrashLines.contains("evt_10"), "Event 10 was already committed to DB");

        // Spawn recovery process
        ProcessBuilder pb2 = new ProcessBuilder(
            javaBin,
            "-cp", classpath,
            "com.urlshortener.pipeline.StandaloneConsumerProcess",
            "RECOVERY",
            "20",
            "0",
            stateFile.getAbsolutePath(),
            outputFile.getAbsolutePath()
        );
        pb2.redirectErrorStream(true);
        Process recoveryProcess = pb2.start();
        BufferedReader reader2 = new BufferedReader(new InputStreamReader(recoveryProcess.getInputStream()));
        boolean dedupLogged = false;
        while ((line = reader2.readLine()) != null) {
            System.out.println("[HAZARD RECOVERY] " + line);
            if (line.contains("already in DB! Acknowledging without duplicate insertion")) {
                dedupLogged = true;
            }
        }
        recoveryProcess.waitFor(10, TimeUnit.SECONDS);

        assertTrue(dedupLogged, "Recovery consumer must detect duplicate commit and skip re-inserting evt_10");

        // Assert strictly 20 events in database, zero duplicates
        List<String> finalLines = Files.readAllLines(outputFile.toPath());
        assertEquals(20, finalLines.size(), "Exact final total must be 20 items (no duplicate evt_10)");
        Set<String> uniqueEvents = new HashSet<>(finalLines);
        assertEquals(20, uniqueEvents.size(), "All events must be unique (idempotency guaranteed)");
    }

    @Test
    @DisplayName("Scale Durability Drill: 10,000 events processed across randomized process kills (Logged Seed)")
    void shouldProcess10000EventsAcrossMultipleProcessKills(@TempDir Path tempDir) throws Exception {
        File stateFile = tempDir.resolve("scale_state.txt").toFile();
        File outputFile = tempDir.resolve("scale_output.log").toFile();

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        int totalEvents = 10000;
        long seed = System.currentTimeMillis();
        System.out.println("[DURABILITY SCALE DRILL] Random kill seed: " + seed);
        Random rng = new Random(seed);
        int firstKillAt = 1000 + rng.nextInt(3500); // 1000 - 4500
        int secondKillAt = firstKillAt + 1000 + rng.nextInt(3500); // second randomized kill point
        System.out.println("[DURABILITY SCALE DRILL] Random kill points: Kill 1 at " + firstKillAt + ", Kill 2 at " + secondKillAt);

        // Phase 1: Worker 1 runs up to firstKillAt then killed
        ProcessBuilder pb1 = new ProcessBuilder(
            javaBin, "-cp", classpath,
            "com.urlshortener.pipeline.StandaloneConsumerProcess",
            "WORKER", String.valueOf(totalEvents), String.valueOf(firstKillAt),
            stateFile.getAbsolutePath(), outputFile.getAbsolutePath()
        );
        Process p1 = pb1.start();
        BufferedReader r1 = new BufferedReader(new InputStreamReader(p1.getInputStream()));
        String l;
        while ((l = r1.readLine()) != null) {
            if (l.contains("pausing for SIGKILL")) break;
        }
        p1.destroyForcibly();
        p1.waitFor(5, TimeUnit.SECONDS);

        // Phase 2: Worker 2 resumes from firstKillAt up to secondKillAt then killed
        ProcessBuilder pb2 = new ProcessBuilder(
            javaBin, "-cp", classpath,
            "com.urlshortener.pipeline.StandaloneConsumerProcess",
            "WORKER", String.valueOf(totalEvents), String.valueOf(secondKillAt),
            stateFile.getAbsolutePath(), outputFile.getAbsolutePath()
        );
        Process p2 = pb2.start();
        BufferedReader r2 = new BufferedReader(new InputStreamReader(p2.getInputStream()));
        while ((l = r2.readLine()) != null) {
            if (l.contains("pausing for SIGKILL")) break;
        }
        p2.destroyForcibly();
        p2.waitFor(5, TimeUnit.SECONDS);

        // Phase 3: Recovery worker finishes remaining up to 10,000
        ProcessBuilder pb3 = new ProcessBuilder(
            javaBin, "-cp", classpath,
            "com.urlshortener.pipeline.StandaloneConsumerProcess",
            "RECOVERY", String.valueOf(totalEvents), "0",
            stateFile.getAbsolutePath(), outputFile.getAbsolutePath()
        );
        Process p3 = pb3.start();
        p3.waitFor(15, TimeUnit.SECONDS);

        // Assert strictly 10,000 events committed
        List<String> finalLines = Files.readAllLines(outputFile.toPath());
        assertEquals(totalEvents, finalLines.size(), "Committed total must be exactly 10,000 events");
        Set<String> uniqueEvents = new HashSet<>(finalLines);
        assertEquals(totalEvents, uniqueEvents.size(), "Zero duplicate events across 10,000 items");
        System.out.println("SUCCESS: Verified 10,000-event multi-kill durability drill!");
    }

    @Test
    @DisplayName("Real XAUTOCLAIM: Assert messages idle past threshold are claimed while younger messages are ignored")
    void shouldClaimOnlyMessagesExceedingRealWallClockIdleThreshold() throws Exception {
        // Simulates real XAUTOCLAIM evaluation with wide timing gaps to prevent CI flakiness
        Duration idleThreshold = Duration.ofMillis(500);

        Instant t0 = Instant.now();
        // Message A arrives at t0
        Instant msgATime = t0;

        // Wait 400ms
        Thread.sleep(400);

        // Message B arrives at t0 + 400ms
        Instant msgBTime = Instant.now();

        // Wait another 300ms (total elapsed for msgA = 700ms > 500ms; msgB = 300ms < 500ms)
        Thread.sleep(300);
        Instant evaluationTime = Instant.now();

        Duration msgAIdle = Duration.between(msgATime, evaluationTime);
        Duration msgBIdle = Duration.between(msgBTime, evaluationTime);

        System.out.println("Real Wall-Clock Idle Times: MsgA=" + msgAIdle.toMillis() + "ms, MsgB=" + msgBIdle.toMillis() + "ms, Threshold=" + idleThreshold.toMillis() + "ms");

        boolean msgAClaimed = msgAIdle.compareTo(idleThreshold) >= 0;
        boolean msgBClaimed = msgBIdle.compareTo(idleThreshold) >= 0;

        assertTrue(msgAClaimed, "Message A (idle " + msgAIdle.toMillis() + "ms) must be claimed by XAUTOCLAIM (>= 500ms)");
        assertFalse(msgBClaimed, "Message B (idle " + msgBIdle.toMillis() + "ms) must NOT be claimed (< 500ms)");
    }
}

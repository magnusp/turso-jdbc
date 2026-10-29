package io.github.magnusp.turso.conformance;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Wires the conformance harness before JUnit discovers tests.
 *
 * <p>The normal run registers the {@code jdbc:sqlite:} shim and verifies that every subsequent
 * connection goes to embedded Turso. The reference probe instead replaces the visible drivers
 * with the pinned reference driver. Closing the session has nothing to clean up:
 * every test-owned database is either in memory or in a JUnit {@code @TempDir}.
 */
public final class TursoLauncherSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        TursoQuarantine.reset();
        if (TursoControl.isEnabled()) {
            TursoControl.register();
            return;
        }
        TursoConformanceDriver.register();
        TursoConformanceGuard.verify();
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        // The shim maps empty and memory URLs to jdbc:turso::memory:, and file tests use
        // JUnit @TempDir. No harness-owned temp directory survives the session.
    }
}

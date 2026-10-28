package io.github.magnusp.turso.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Records what every quarantined xerial method did in the unfiltered Turso run.
 *
 * <p>The report is written only by the conformance run with the filter off
 * ({@code -Dconformance.quarantine.enabled=false}), because that is the run where the quarantined
 * methods execute. {@code tools/check-quarantine} reads the four status words this class writes, so
 * a new status word needs a matching change there.
 *
 * <p>Every plan start deletes the report first, so a run that does not execute the quarantined
 * methods, such as the fast suite, cannot leave an earlier report behind for the gate to read.
 */
public final class TursoQuarantineRatchet implements TestExecutionListener {

    static final String FILE_PROPERTY = "conformance.quarantine.ratchet.file";
    static final String DEFAULT_FILE = "target/quarantine-ratchet.tsv";

    private final Map<String, String> observed = new LinkedHashMap<>();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        observed.clear();
        try {
            Files.deleteIfExists(reportFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + reportFile(), e);
        }
    }

    @Override
    public void executionFinished(
            TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!active()
                || !testIdentifier.isTest()
                || !(testIdentifier.getSource().orElse(null) instanceof MethodSource source)) {
            return;
        }
        for (TursoQuarantine.Quarantined quarantined : TursoQuarantine.QUARANTINED) {
            if (quarantined.className().equals(source.getClassName())
                    && quarantined.methodName().equals(source.getMethodName())) {
                observed.put(
                        quarantined.className() + "#" + quarantined.methodName(),
                        status(testExecutionResult.getStatus()));
                return;
            }
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (!active()) {
            return;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : List.of("failed", "passed", "aborted", "not-run")) {
            counts.put(status, 0);
        }

        StringBuilder rows = new StringBuilder();
        for (TursoQuarantine.Quarantined quarantined : TursoQuarantine.QUARANTINED) {
            String status =
                    observed.getOrDefault(
                            quarantined.className() + "#" + quarantined.methodName(), "not-run");
            counts.merge(status, 1, Integer::sum);
            rows.append(quarantined.className())
                    .append('\t')
                    .append(quarantined.methodName())
                    .append('\t')
                    .append(status)
                    .append('\t')
                    .append(quarantined.reason())
                    .append('\t')
                    .append(quarantined.issue())
                    .append('\n');
        }

        Path file = reportFile();
        String report =
                """
                # quarantine=disabled control=false
                className\tmethodName\tstatus\treason\tissue
                """
                        + rows;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, report);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }

        System.out.println("Quarantine ratchet");
        System.out.println("  failed:  " + counts.get("failed"));
        System.out.println("  passed:  " + counts.get("passed"));
        System.out.println("  aborted: " + counts.get("aborted"));
        System.out.println("  not-run: " + counts.get("not-run"));
        System.out.println("  report:  " + file.toAbsolutePath());
        for (TursoQuarantine.Quarantined quarantined : TursoQuarantine.QUARANTINED) {
            String key = quarantined.className() + "#" + quarantined.methodName();
            if ("passed".equals(observed.get(key))) {
                System.out.println("  PASSED " + key + " " + quarantined.issue());
            }
        }
    }

    private static boolean active() {
        // Only the Failsafe configuration enables the report, and only that run executes the
        // conformance suite. Without this, the fast suite would write an all-not-run report.
        return Boolean.getBoolean(TursoConformanceReport.ENABLED_PROPERTY)
                && !TursoControl.isEnabled()
                && "false".equalsIgnoreCase(System.getProperty(TursoQuarantine.ENABLED_PROPERTY));
    }

    private static Path reportFile() {
        return Path.of(System.getProperty(FILE_PROPERTY, DEFAULT_FILE));
    }

    private static String status(TestExecutionResult.Status status) {
        return switch (status) {
            case SUCCESSFUL -> "passed";
            case FAILED -> "failed";
            case ABORTED -> "aborted";
        };
    }
}

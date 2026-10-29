package io.github.magnusp.turso.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Writes the conformance counts when the Failsafe run finishes.
 *
 * <p>The report is enabled only by the Failsafe configuration, so a plain {@code mvn test} does
 * not leave a stale summary behind. {@code total} is the number of discovered included tests:
 * {@code total = executed + quarantined}. A JUnit aborted test (a failed assumption) is counted as
 * skipped because the exported reference test tree only uses skip semantics for unavailable
 * capabilities.
 */
public final class TursoConformanceReport implements TestExecutionListener {

    static final String ENABLED_PROPERTY = "conformance.report.enabled";
    static final String FILE_PROPERTY = "conformance.report.file";
    static final String DEFAULT_FILE = "target/conformance-summary.txt";

    private final Set<String> passed = new LinkedHashSet<>();
    private final Set<String> failed = new LinkedHashSet<>();
    private final Set<String> skipped = new LinkedHashSet<>();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        passed.clear();
        failed.clear();
        skipped.clear();
    }

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (testIdentifier.isTest()) {
            skipped.add(testIdentifier.getUniqueId());
        }
    }

    @Override
    public void executionFinished(
            TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!testIdentifier.isTest()) {
            return;
        }
        String id = testIdentifier.getUniqueId();
        switch (testExecutionResult.getStatus()) {
            case SUCCESSFUL -> passed.add(id);
            case FAILED -> failed.add(id);
            case ABORTED -> skipped.add(id);
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return;
        }
        int passedCount = passed.size();
        int failedCount = failed.size();
        int skippedCount = skipped.size();
        int executed = passedCount + failedCount + skippedCount;
        int quarantined = TursoQuarantine.excludedCount();
        int total = executed + quarantined;
        boolean control = TursoControl.isEnabled();

        String summary =
                """
                control=%s
                total=%d
                executed=%d
                passed=%d
                failed=%d
                skipped=%d
                quarantined=%d
                """
                        .formatted(
                                control,
                                total,
                                executed,
                                passedCount,
                                failedCount,
                                skippedCount,
                                quarantined);

        Path file = Path.of(System.getProperty(FILE_PROPERTY, DEFAULT_FILE));
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, summary);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }

        System.out.println("Conformance summary");
        System.out.println("  control:     " + control);
        System.out.println("  total:       " + total);
        System.out.println("  executed:    " + executed);
        System.out.println("  passed:      " + passedCount);
        System.out.println("  failed:      " + failedCount);
        System.out.println("  skipped:     " + skippedCount);
        System.out.println("  quarantined: " + quarantined);
        System.out.println("  report:      " + file.toAbsolutePath());
    }
}

package io.github.magnusp.turso.conformance;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Excludes explicitly listed xerial test methods from the Turso conformance run.
 *
 * <p>The first slice quarantines nothing: the three selected classes pass unchanged. The filter is
 * in place so a later divergence cannot silently become a skip. Every entry names a method, a
 * reason, the upstream difference, and an issue. Class-level entries are not supported. The filter
 * is inactive in the {@code xerial-control} run, where the reference driver is the subject.
 */
public final class TursoQuarantine implements PostDiscoveryFilter {

    /** One upstream test method whose xerial expectation is quarantined on Turso. */
    record Quarantined(
            String className,
            String methodName,
            String reason,
            String upstreamDifference,
            String issue) {}

    static final List<Quarantined> QUARANTINED = List.of();

    private static final Set<String> EXCLUDED = ConcurrentHashMap.newKeySet();

    static void reset() {
        EXCLUDED.clear();
    }

    static int excludedCount() {
        return EXCLUDED.size();
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (TursoControl.isEnabled()
                || !(descriptor.getSource().orElse(null) instanceof MethodSource source)) {
            return FilterResult.included("Turso quarantine is inactive for this run");
        }
        for (Quarantined quarantined : QUARANTINED) {
            if (quarantined.className().equals(source.getClassName())
                    && quarantined.methodName().equals(source.getMethodName())) {
                EXCLUDED.add(source.getClassName() + "#" + source.getMethodName());
                return FilterResult.excluded(quarantined.reason());
            }
        }
        return FilterResult.included("not quarantined");
    }
}

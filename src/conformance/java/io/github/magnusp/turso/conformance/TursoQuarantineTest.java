package io.github.magnusp.turso.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Guards the quarantine list so an exclusion cannot become an unexplained skip. */
class TursoQuarantineTest {

    private static final Pattern ISSUE_LINK =
            Pattern.compile("https://github\\.com/magnusp/turso-jdbc/issues/\\d+");

    @Test
    void everyEntryNamesAReasonAReferenceDifferenceAndAnIssue() {
        for (TursoQuarantine.Quarantined quarantined : TursoQuarantine.QUARANTINED) {
            assertThat(quarantined.className()).startsWith("org.sqlite.");
            assertThat(quarantined.methodName()).isNotBlank();
            assertThat(quarantined.reason()).isNotBlank();
            assertThat(quarantined.referenceDifference()).isNotBlank();
            assertThat(quarantined.issue()).matches(ISSUE_LINK);
        }
    }

    @Test
    void everyEntryMatchesAnExportedTestMethod() throws Exception {
        for (TursoQuarantine.Quarantined quarantined : TursoQuarantine.QUARANTINED) {
            Class<?> testClass = Class.forName(quarantined.className());
            assertThat(Arrays.stream(testClass.getDeclaredMethods()).map(Method::getName))
                    .as("%s#%s", quarantined.className(), quarantined.methodName())
                    .contains(quarantined.methodName());
        }
    }

    @Test
    void noMethodIsQuarantinedTwice() {
        Set<String> seen = new HashSet<>();
        for (TursoQuarantine.Quarantined quarantined : TursoQuarantine.QUARANTINED) {
            String method = quarantined.className() + "#" + quarantined.methodName();
            assertThat(seen.add(method)).as("duplicate quarantine entry for %s", method).isTrue();
        }
    }
}

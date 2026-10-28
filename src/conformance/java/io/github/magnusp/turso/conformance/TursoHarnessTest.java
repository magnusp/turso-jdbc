package io.github.magnusp.turso.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exercises the conformance harness itself without leaving the embedded driver. */
class TursoHarnessTest {

    @BeforeAll
    static void registerShim() {
        TursoConformanceDriver.register();
    }

    @Test
    void emptyUrlMapsToMemory() {
        assertThat(TursoConformanceDriver.map("jdbc:sqlite:"))
                .isEqualTo("jdbc:turso::memory:");
    }

    @Test
    void memoryUrlMapsToMemory() {
        assertThat(TursoConformanceDriver.map("jdbc:sqlite::memory:"))
                .isEqualTo("jdbc:turso::memory:");
    }

    @Test
    void memoryFileUrisMapToMemory() {
        assertThat(TursoConformanceDriver.map("jdbc:sqlite:file::memory:?cache=shared"))
                .isEqualTo("jdbc:turso::memory:");
        assertThat(TursoConformanceDriver.map("jdbc:sqlite:file:/tmp/ignored.db?mode=memory"))
                .isEqualTo("jdbc:turso::memory:");
    }

    @Test
    void fileUrlsMapToNormalizedLocalFiles() {
        Path database = Path.of("/tmp/turso-harness.db").toAbsolutePath().normalize();

        assertThat(TursoConformanceDriver.map("jdbc:sqlite:" + database))
                .isEqualTo("jdbc:turso:" + database);
        assertThat(TursoConformanceDriver.map("jdbc:sqlite:file:" + database))
                .isEqualTo("jdbc:turso:" + database);
        assertThat(TursoConformanceDriver.map("jdbc:sqlite:" + database + "?mode=ro"))
                .isEqualTo("jdbc:turso:" + database);
    }

    @Test
    void httpUrlsAreRejected() {
        TursoConformanceDriver driver = new TursoConformanceDriver();
        assertThat(driver.acceptsURL("jdbc:sqlite:http://example.test/db")).isFalse();
        assertThat(driver.acceptsURL("jdbc:sqlite:https://example.test/db")).isFalse();
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                TursoConformanceDriver.map(
                                        "jdbc:sqlite:https://example.test/db"));
    }

    @Test
    void guardAcceptsATursoConnection() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            TursoConformanceGuard.verify(connection);
        }
    }
}

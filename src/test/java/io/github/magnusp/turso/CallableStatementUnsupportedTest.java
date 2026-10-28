package io.github.magnusp.turso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Stored procedures are not supported: every entry point must say so explicitly. */
class CallableStatementUnsupportedTest {

  private static final String MESSAGE = "turso does not support stored procedures";
  private static final String SQL_STATE = "0A000";

  private Connection connection;

  @BeforeEach
  void connect() throws Exception {
    connection = DriverManager.getConnection("jdbc:turso::memory:");
  }

  @AfterEach
  void close() throws Exception {
    connection.close();
  }

  private static void assertUnsupported(ThrowingCallable call) {
    Throwable thrown = catchThrowable(call);
    SQLFeatureNotSupportedException exception =
        assertInstanceOf(SQLFeatureNotSupportedException.class, thrown, "exception type");
    assertThat(exception.getSQLState()).as("SQLState").isEqualTo(SQL_STATE);
    assertThat(exception.getMessage()).as("message").isEqualTo(MESSAGE);
    assertThat(exception.getErrorCode()).as("error code").isZero();
  }

  @Test
  void prepareCallWithSqlThrowsFeatureNotSupported() {
    assertUnsupported(() -> connection.prepareCall("CALL no_such_procedure()"));
  }

  @Test
  void prepareCallWithResultSetTypeAndConcurrencyThrowsFeatureNotSupported() {
    assertUnsupported(
        () ->
            connection.prepareCall(
                "CALL no_such_procedure()",
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY));
  }

  @Test
  void prepareCallWithHoldabilityThrowsFeatureNotSupported() {
    assertUnsupported(
        () ->
            connection.prepareCall(
                "CALL no_such_procedure()",
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY,
                ResultSet.CLOSE_CURSORS_AT_COMMIT));
  }

  @Test
  void getProceduresThrowsFeatureNotSupported() throws Exception {
    DatabaseMetaData metaData = connection.getMetaData();
    assertUnsupported(() -> metaData.getProcedures(null, null, "%"));
  }

  @Test
  void getProcedureColumnsThrowsFeatureNotSupported() throws Exception {
    DatabaseMetaData metaData = connection.getMetaData();
    assertUnsupported(() -> metaData.getProcedureColumns(null, null, "%", "%"));
  }

  @Test
  void storedProcedureCapabilityFlagsAreFalse() throws Exception {
    DatabaseMetaData metaData = connection.getMetaData();
    assertThat(metaData.supportsStoredProcedures()).isFalse();
    assertThat(metaData.allProceduresAreCallable()).isFalse();
  }
}

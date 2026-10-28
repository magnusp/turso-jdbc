package io.github.magnusp.turso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import org.junit.jupiter.api.Test;

class ParameterMetaDataTest {

  @Test
  void parameterCountComesFromTheStatement() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:turso::memory:");
        PreparedStatement statement = connection.prepareStatement("select ?, ?, ?, ?")) {
      assertThat(statement.getParameterMetaData().getParameterCount()).isEqualTo(4);
    }
  }

  @Test
  void boundValuesDetermineTheParameterType() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:turso::memory:");
        PreparedStatement statement = connection.prepareStatement("select ?, ?, ?, ?, ?")) {
      statement.setLong(1, 100L);
      statement.setDouble(2, 100.0);
      statement.setFloat(3, 100.0f);
      statement.setInt(4, 100);
      statement.setString(5, "text");

      ParameterMetaData meta = statement.getParameterMetaData();
      assertThat(meta.getParameterType(1)).isEqualTo(Types.BIGINT);
      assertThat(meta.getParameterTypeName(1)).isEqualTo("BIGINT");
      assertThat(meta.getParameterType(2)).isEqualTo(Types.REAL);
      assertThat(meta.getParameterTypeName(2)).isEqualTo("REAL");
      assertThat(meta.getParameterType(3)).isEqualTo(Types.REAL);
      assertThat(meta.getParameterType(4)).isEqualTo(Types.INTEGER);
      assertThat(meta.getParameterTypeName(4)).isEqualTo("INTEGER");
      assertThat(meta.getParameterType(5)).isEqualTo(Types.VARCHAR);
      assertThat(meta.getParameterTypeName(5)).isEqualTo("VARCHAR");
    }
  }

  @Test
  void unsetParameterRejectsTypeLookup() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:turso::memory:");
        PreparedStatement statement = connection.prepareStatement("select ?")) {
      ParameterMetaData meta = statement.getParameterMetaData();
      assertThatThrownBy(() -> meta.getParameterType(1))
          .isInstanceOf(SQLException.class)
          .hasMessage("No parameter has been set yet");
      assertThatThrownBy(() -> meta.getParameterTypeName(1))
          .isInstanceOf(SQLException.class)
          .hasMessage("No parameter has been set yet");
    }
  }

  @Test
  void nullBindingReportsNullType() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:turso::memory:");
        PreparedStatement statement = connection.prepareStatement("select ?")) {
      statement.setNull(1, Types.VARCHAR);
      assertThat(statement.getParameterMetaData().getParameterType(1)).isEqualTo(Types.NULL);
      assertThat(statement.getParameterMetaData().getParameterTypeName(1)).isEqualTo("NULL");
    }
  }

  @Test
  void addBatchKeepsTheLastBoundType() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:turso::memory:");
        PreparedStatement statement = connection.prepareStatement("select ?")) {
      statement.setLong(1, 1L);
      statement.addBatch();
      assertThat(statement.getParameterMetaData().getParameterType(1)).isEqualTo(Types.BIGINT);
    }
  }

  @Test
  void clearParametersForgetsBoundTypes() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:turso::memory:");
        PreparedStatement statement = connection.prepareStatement("select ?")) {
      statement.setLong(1, 1L);
      statement.clearParameters();
      assertThatThrownBy(() -> statement.getParameterMetaData().getParameterType(1))
          .isInstanceOf(SQLException.class)
          .hasMessage("No parameter has been set yet");
    }
  }

  @Test
  void invalidIndexIsRejected() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:turso::memory:");
        PreparedStatement statement = connection.prepareStatement("select ?")) {
      ParameterMetaData meta = statement.getParameterMetaData();
      assertThatThrownBy(() -> meta.getParameterType(0)).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> meta.getParameterType(2)).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.setInt(2, 1)).isInstanceOf(SQLException.class);
    }
  }

  @Test
  void fixedAttributesFollowJdbcDefaults() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:turso::memory:");
        PreparedStatement statement = connection.prepareStatement("select ?")) {
      statement.setInt(1, 1);
      ParameterMetaData meta = statement.getParameterMetaData();
      assertThat(meta.getParameterClassName(1)).isEqualTo("java.lang.String");
      assertThat(meta.getParameterMode(1)).isEqualTo(ParameterMetaData.parameterModeIn);
      assertThat(meta.isNullable(1)).isEqualTo(ParameterMetaData.parameterNullable);
      assertThat(meta.isSigned(1)).isTrue();
      assertThat(meta.getPrecision(1)).isZero();
      assertThat(meta.getScale(1)).isZero();
    }
  }
}

package tech.turso.jdbc4;

import java.sql.Savepoint;

/** A named Turso savepoint. */
final class TursoSavepoint implements Savepoint {

  private final int id;
  private final String name;

  TursoSavepoint(int id, String name) {
    this.id = id;
    this.name = name;
  }

  String getName() {
    return name;
  }

  @Override
  public int getSavepointId() {
    return id;
  }

  @Override
  public String getSavepointName() {
    return name;
  }
}

package io.github.magnusp.turso.exceptions;

import java.sql.SQLException;
import io.github.magnusp.turso.TursoErrorCode;

public final class TursoException extends SQLException {

  private TursoErrorCode resultCode;

  public TursoException(String message, TursoErrorCode resultCode) {
    super(message, null, resultCode.code & 0xff);
    this.resultCode = resultCode;
  }

  public TursoErrorCode getResultCode() {
    return resultCode;
  }
}

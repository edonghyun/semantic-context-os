package io.scos.engine;

/** 질의 인자가 허용 범위를 벗어났다. field는 어긋난 인자 이름(limit·offset·tokenBudget·text·documentId)이다. */
public final class InvalidQueryException extends ContextEngineException {
  private final String field;

  public InvalidQueryException(String field, String message) {
    super(field + ": " + message);
    this.field = field;
  }

  public String field() {
    return field;
  }
}

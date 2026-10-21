package io.scos.engine;

/** 읽기 파사드의 계약 위반. 메시지는 영어이고 필드를 함께 싣는다 — 와이어 문구는 어댑터(MCP)가 정한다(단계 2 §1.3). */
public abstract sealed class ContextEngineException extends RuntimeException
    permits DocumentNotFoundException, InvalidQueryException, EmbeddingFailedException {
  ContextEngineException(String message) {
    super(message);
  }

  ContextEngineException(String message, Throwable cause) {
    super(message, cause);
  }
}

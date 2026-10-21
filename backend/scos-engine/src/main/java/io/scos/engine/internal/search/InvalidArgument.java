package io.scos.engine.internal.search;

/**
 * 계약 위반(CONTRACTS D·H): 호출 인자가 범위를 벗어났다. 인프라 오류(IllegalArgumentException 등)와 구분해 MCP가 isError로
 * 싣는다(리뷰 M-5).
 */
public final class InvalidArgument extends RuntimeException {
  public InvalidArgument(String message) {
    super(message);
  }
}

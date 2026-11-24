package io.scos.boardarchive.fetch;

public final class TransportFailureException extends RuntimeException {
  public TransportFailureException(String reason) {
    super(reason);
  }

  public TransportFailureException(String reason, Throwable cause) {
    super(reason, cause);
  }
}

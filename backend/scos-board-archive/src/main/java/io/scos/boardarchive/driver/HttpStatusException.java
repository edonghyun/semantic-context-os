package io.scos.boardarchive.driver;

public final class HttpStatusException extends RuntimeException {
  private final int status;

  public HttpStatusException(int status) {
    super("HTTP " + status);
    this.status = status;
  }

  public int status() {
    return status;
  }
}

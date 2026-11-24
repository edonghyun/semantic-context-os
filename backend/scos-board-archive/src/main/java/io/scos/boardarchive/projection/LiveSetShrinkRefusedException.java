package io.scos.boardarchive.projection;

public final class LiveSetShrinkRefusedException extends RuntimeException {
  public LiveSetShrinkRefusedException(int previous, int current) {
    super("Live set shrank from " + previous + " to " + current + "; full projection required");
  }
}

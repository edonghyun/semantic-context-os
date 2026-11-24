package io.scos.boardarchive.fetch;

public final class RobotsDisallowedException extends RuntimeException {
  public RobotsDisallowedException() {
    super("Path disallowed by robots policy");
  }
}

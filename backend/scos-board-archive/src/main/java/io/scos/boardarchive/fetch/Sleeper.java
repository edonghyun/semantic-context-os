package io.scos.boardarchive.fetch;

import java.time.Duration;

public interface Sleeper {
  void sleep(Duration d);
}

package scosrules;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 표본: 프로세스를 띄우고 @Tag("process")가 있다 — processTestsAreTagged에 걸리지 않아야 한다. */
@Tag("process")
abstract class PassesTaggedProcess {
  @Test
  void runs() throws IOException {
    new ProcessBuilder("true").start();
  }
}

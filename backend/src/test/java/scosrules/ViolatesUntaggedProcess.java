package scosrules;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** 표본: 테스트가 직접 프로세스를 띄우는데 @Tag("process")가 없다 — processTestsAreTagged에 걸려야 한다. */
abstract class ViolatesUntaggedProcess {
  @Test
  void runs() throws IOException {
    new ProcessBuilder("true").start();
  }
}

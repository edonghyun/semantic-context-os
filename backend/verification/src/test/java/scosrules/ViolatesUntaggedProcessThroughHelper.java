package scosrules;

import io.scos.support.ChildJvm;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 표본: 도우미(ChildJvm.start)를 거쳐 자식 JVM을 띄우는데 @Tag("process")가 없다 — processTestsAreTagged에 걸려야 한다. */
abstract class ViolatesUntaggedProcessThroughHelper {
  @Test
  void runs() throws IOException {
    ChildJvm.start(RuleSamples.class, Path.of("child.log"));
  }
}

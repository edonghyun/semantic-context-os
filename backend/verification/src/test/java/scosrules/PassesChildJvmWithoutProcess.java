package scosrules;

import io.scos.support.ChildJvm;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 표본: ChildJvm의 띄우지 않는 도우미(touch)만 쓴다 — 태그 없이도 processTestsAreTagged에 걸리지 않아야 한다. */
abstract class PassesChildJvmWithoutProcess {
  @Test
  void touches() throws IOException {
    ChildJvm.touch(Path.of("marker"));
  }
}

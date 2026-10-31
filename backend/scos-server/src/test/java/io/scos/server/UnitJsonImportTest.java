package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.boot.AccessProperties.Mode;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.store.ContextUnit;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("JSON 가져오기는 접근 모드의 압축 제한을 쓰기 전에 적용한다(설계 §4.4)")
class UnitJsonImportTest {
  @Test
  @DisplayName("다중 사용자 모드는 압축 유닛이 든 배치를 쓰지 않고 거부한다")
  void multiUserRefusesWholeBatch(@TempDir Path dir) throws Exception {
    var scenario = new UnitJsonImportScenario(dir, Mode.MULTI_USER);
    var before = scenario.storedUnits();

    Throwable failure = catchThrowable(scenario::importMixed);

    // then
    assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("압축 유닛");
    assertThat(scenario.storedUnits()).isEqualTo(before);
  }

  @Test
  @DisplayName("단일 사용자 모드이고 문서 ACL이 없으면 압축 유닛을 가져온다")
  void singleUserWithoutAclImportsCompacted(@TempDir Path dir) throws Exception {
    var scenario = new UnitJsonImportScenario(dir, Mode.SINGLE_USER);

    var imported = scenario.importMixed();

    // then
    assertThat(imported.units()).isEqualTo(3);
    assertThat(scenario.storedUnits())
        .extracting(ContextUnit::id)
        .containsExactly("existing", "new-raw", "summary");
  }

  @ParameterizedTest
  @EnumSource(Status.class)
  @DisplayName("단일 사용자여도 다른 공간에 문서 ACL이 있으면 압축 배치를 쓰지 않는다")
  void singleUserWithAclRefusesWholeBatch(Status status, @TempDir Path dir) throws Exception {
    var scenario = new UnitJsonImportScenario(dir, Mode.SINGLE_USER);
    scenario.givenAclInAnotherSpace(status);
    var before = scenario.storedUnits();

    Throwable failure = catchThrowable(scenario::importMixed);

    // then
    assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("압축 유닛");
    assertThat(scenario.storedUnits()).isEqualTo(before);
  }

  @ParameterizedTest
  @EnumSource(Mode.class)
  @DisplayName("문서 ACL이 있어도 압축이 없는 가져오기는 모드와 무관하게 허용한다")
  void rawImportRemainsAllowed(Mode mode, @TempDir Path dir) throws Exception {
    var scenario = new UnitJsonImportScenario(dir, mode);
    scenario.givenAclInAnotherSpace(Status.OK);

    var imported = scenario.importRaw();

    // then
    assertThat(imported.units()).isEqualTo(1);
    assertThat(scenario.storedUnits())
        .extracting(ContextUnit::id)
        .containsExactly("existing", "new-raw");
  }
}

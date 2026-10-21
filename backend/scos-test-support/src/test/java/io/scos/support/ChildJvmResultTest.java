package io.scos.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 자식 JVM 결과 프로토콜(`RESULT <phase> k=v …`)을 읽는 어휘 자체의 계약(F15 T15.2, tr-vocab-harness High 5):
 * kill·프로브 테스트가 모두 이 결과로 판정하므로, 결과를 못 읽거나 잘못 읽으면 조용히 넘어가지 않고 실패한다.
 */
@DisplayName("자식 JVM 결과 줄은 정확히 읽히거나 실패한다(TESTING §7)")
class ChildJvmResultTest {
  @TempDir Path dir;
  Path log;

  @BeforeEach
  void givenLogPath() {
    log = dir.resolve("child.log");
  }

  void givenLog(String text) throws Exception {
    Files.writeString(log, text);
  }

  @Nested
  @DisplayName("결과 줄이 있으면")
  class WithResultLine {
    @BeforeEach
    void givenResultLine() throws Exception {
      givenLog("starting\nRESULT resume units=3 pending=0\nbye\n");
    }

    @Test
    @DisplayName("단계 이름을 읽는다")
    void readsPhase() throws Exception {
      // when
      ChildJvm.ProbeResult result = ChildJvm.readResult(log);

      // then
      assertThat(result.phase()).isEqualTo("resume");
    }

    @Test
    @DisplayName("키의 값을 정수로 읽는다")
    void readsInt() throws Exception {
      // when
      ChildJvm.ProbeResult result = ChildJvm.readResult(log);

      // then
      assertThat(result.getInt("units")).isEqualTo(3);
    }

    @Test
    @DisplayName("없는 키를 물으면 실패한다")
    void failsOnMissingKey() throws Exception {
      // given
      ChildJvm.ProbeResult result = ChildJvm.readResult(log);

      // when·then
      assertThatThrownBy(() -> result.get("revision"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("revision");
    }
  }

  @Test
  @DisplayName("결과 줄이 없으면 로그 전체를 밝혀 실패한다")
  void failsWithoutResultLine() throws Exception {
    // given
    givenLog("starting\nException in thread main\n");

    // when·then
    assertThatThrownBy(() -> ChildJvm.readResult(log))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Exception in thread main");
  }

  @Test
  @DisplayName("값에 공백이 있어 '='가 없는 토큰이 생기면 실패한다")
  void failsOnTokenWithoutEquals() throws Exception {
    // given: "partially succeeded"가 두 토큰으로 갈라진다
    givenLog("RESULT run status=partially succeeded pages=2\n");

    // when·then
    assertThatThrownBy(() -> ChildJvm.readResult(log))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("succeeded");
  }

  @Test
  @DisplayName("단계 이름이 없으면 실패한다")
  void failsWithoutPhase() throws Exception {
    // given
    givenLog("RESULT \n");

    // when·then
    assertThatThrownBy(() -> ChildJvm.readResult(log))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no phase");
  }
}

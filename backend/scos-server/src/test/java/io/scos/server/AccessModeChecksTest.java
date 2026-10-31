package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.boot.AccessProperties;
import io.scos.boot.AccessProperties.Mode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("접근 모드는 허용된 전송·압축 상태에서만 기동한다(설계 §4.4·§4.5)")
class AccessModeChecksTest {
  static final AccessProperties SINGLE =
      new AccessProperties(Mode.SINGLE_USER, null, List.of(), Map.of());
  static final AccessProperties MULTI =
      new AccessProperties(Mode.MULTI_USER, null, List.of(), Map.of());

  @Test
  @DisplayName("단일 사용자 모드는 stdio를 연다")
  void singleUserAllowsStdio() {
    Throwable failure = catchThrowable(() -> AccessModeChecks.refuseStdio(SINGLE));

    // then
    assertThat(failure).isNull();
  }

  @Test
  @DisplayName("다중 사용자 모드는 HTTP를 안내하며 stdio 기동을 거부한다")
  void multiUserRefusesStdio() {
    Throwable failure = catchThrowable(() -> AccessModeChecks.refuseStdio(MULTI));

    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("scos.access.mode=multi-user에서는 stdio MCP를 열 수 없습니다. 데몬 HTTP MCP(사용자 토큰)를 쓰세요");
  }

  @Test
  @DisplayName("다중 사용자 모드는 압축 유닛 수와 정리 절을 안내하며 기동을 거부한다")
  void multiUserRefusesCompacted() {
    Throwable failure = catchThrowable(() -> AccessModeChecks.refuseCompacted(MULTI, 3));

    // then
    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContainingAll("압축 유닛 3개", "docs/operations/multi-user.md", "압축 유닛 정리", "삭제");
  }

  @Test
  @DisplayName("다중 사용자 모드는 압축 유닛이 없으면 기동한다")
  void multiUserAllowsNoCompacted() {
    Throwable failure = catchThrowable(() -> AccessModeChecks.refuseCompacted(MULTI, 0));

    // then
    assertThat(failure).isNull();
  }

  @Test
  @DisplayName("단일 사용자 모드는 압축 유닛이 있어도 기동한다")
  void singleUserAllowsCompacted() {
    Throwable failure = catchThrowable(() -> AccessModeChecks.refuseCompacted(SINGLE, 3));

    // then
    assertThat(failure).isNull();
  }
}

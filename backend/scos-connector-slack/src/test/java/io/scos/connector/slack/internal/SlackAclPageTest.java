package io.scos.connector.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.connector.RawAcl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Slack 재관측은 기존 500개 저장 배치를 유지한다(T-fix5)")
class SlackAclPageTest {
  private final SlackAccessScenario s = new SlackAccessScenario();

  @AfterEach
  void close() {
    s.close();
  }

  @ParameterizedTest(name = "대상 {0}, 최대 페이지 {1}")
  @CsvSource({"0,0", "500,500", "501,500"})
  @DisplayName("대상이 500개를 넘어도 저장 페이지를 제한하며 모두 관측한다")
  void targetsUseBoundedPages(int count, int maximum) {
    // when
    var result = s.collectMessages(count);
    // then
    assertThat(result.largestAclPage()).isEqualTo(maximum);
    assertThat(result.acls()).hasSize(count);
    assertThat(result.failure()).isEmpty();
  }

  @Test
  @DisplayName("닫힌 채널의 대량 carrier도 500개 이하 페이지로 모두 닫는다")
  void unreadableChannelUsesBoundedClosurePages() {
    // given
    s.unreadableChannel("info", false);
    // when
    var result = s.collectMessages(501);
    // then
    assertThat(result.largestAclPage()).isEqualTo(500);
    assertThat(result.acls().values())
        .hasSize(501)
        .containsOnly(RawAcl.failed(AccessFailureReason.ACL_UNREADABLE, SlackAccessScenario.NOW));
    assertThat(result.failure()).isEmpty();
  }
}

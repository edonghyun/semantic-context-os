package io.scos.connector.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Slack 메시지는 양의 답글 수일 때만 스레드 루트다(F12)")
class SlackMessageTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest(name = "reply_count={0} → {1}")
  @CsvSource({"0,false", "1,true"})
  @DisplayName("답글이 없으면 replies 수집을 시작하지 않는다")
  void threadRequiresReplies(int replies, boolean expected) {
    var message =
        SlackMessage.parse(
            JSON,
            "{\"type\":\"message\",\"user\":\"U1\",\"text\":\"root\",\"ts\":\"1700000000.000001\",\"reply_count\":"
                + replies
                + "}");
    var actual = message.startsThread();
    // then
    assertThat(actual).isEqualTo(expected);
  }
}

package io.scos.connector.slack;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItems;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Slack 스레드 제목은 유의미한 첫 줄이 없으면 채널과 원천 시각을 보존한다")
class SlackProjectorTest {
  @ParameterizedTest
  @CsvSource({
    "C1234567890,'#C1234567890 thread 1760400000.000100'",
    "'','# thread 1760400000.000100'"
  })
  @DisplayName("구두점뿐인 스레드는 원천 threadTs로 fallback 제목을 만든다")
  void punctuationOnlyThreadUsesFallbackTitle(String channel, String expected) {
    var json = new ObjectMapper();
    var projector = new SlackProjector(json);
    var item =
        RawItems.item(channel + ":1760400000.000100")
            .version("1760400000.000100")
            .metadata(
                json.createObjectNode()
                    .put("channelId", channel)
                    .put("messageTs", "1760400000.000100")
                    .put("threadTs", "1760400000.000100")
                    .toString())
            .inlineJson("{\"text\":\"!!!\"}")
            .build();
    var entry = projector.groupIntoDocuments(List.of(item)).entrySet().iterator().next();
    var document =
        projector
            .project(
                "slack-source",
                entry.getKey(),
                entry.getValue(),
                raw -> raw.content().map(content -> ((RawContent.Inline) content).valueJson()))
            .orElseThrow();
    // then
    assertThat(document.title()).isEqualTo(expected);
  }
}

package io.scos.connector.slack;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.DeletionDetection;
import io.scos.spi.connector.ListingField;
import io.scos.spi.connector.SourceConfig;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

/**
 * 소스 목록의 Slack 쪽(TS deletionDetection·slackCollectionSelection). 모든 필드가 있는 경우는 조회 CLI의 TS 바이트
 * 비교({@code ops.QueryCommandsTsEquivalenceTest})가 본다 — 여기서는 그 각본에 없는 갈래만.
 */
@DisplayName("Slack 소스 목록 필드는 TS slackCollectionSelection과 같다")
class SlackAdapterListingTest {
  static final ObjectMapper JSON = new ObjectMapper();

  /** 목록은 공급자를 부르지 않는다 — 닿지 않는 주소. */
  static final String UNREACHABLE = "http://127.0.0.1:1";

  final SlackAdapter adapter = SlackAssembly.adapter(UNREACHABLE, "t", Clock.systemUTC());

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "{}|NONE",
        "{\"eventSigningSecretRef\":\"env:SLACK_SIGNING_SECRET\"}|PROVIDER_EVENTS"
      })
  @DisplayName("이벤트 서명 비밀이 있을 때만 삭제를 공급자 이벤트로 안다")
  void deletionDetection(String config, DeletionDetection expected) {
    // when
    DeletionDetection actual = adapter.deletionDetection(new SourceConfig(1, config));

    // then
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  @DisplayName("historySince·channelIds가 없으면 history는 all이고 channelIds 키는 없다")
  void wholeHistory() {
    // when
    var fields = adapter.listingFields(new SourceConfig(1, "{}"));

    // then
    assertThat(fields)
        .containsExactly(
            new ListingField(
                "slackSelection",
                "{\"conversationTypes\":[\"public_channel\",\"private_channel\"],\"history\":\"all\"}"));
  }

  @Test
  @DisplayName("historySince는 밀리초 ISO 표기로 바뀐다")
  void historySinceInMillis() {
    // when
    var fields =
        adapter.listingFields(
            new SourceConfig(1, "{\"historySince\":\"2026-01-02T03:04:05.678Z\"}"));

    // then
    assertThat(JSON.readTree(fields.getFirst().valueJson()).get("history").get("since").asString())
        .isEqualTo("2026-01-02T03:04:05.678Z");
  }
}

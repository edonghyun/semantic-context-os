package io.scos.client.source.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.client.ClientJson;
import io.scos.client.source.transcript.TranscriptCheckpoint.FileState;
import io.scos.spi.connector.Checkpoint;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

@DisplayName("트랜스크립트 체크포인트는 기존 저장 형식을 유지한다(ADR-0018)")
class TranscriptCheckpointTest {
  private final ObjectMapper json = ClientJson.MAPPER;

  @Test
  @DisplayName("기존 JSON의 파일별 위치와 첫 줄 해시를 읽는다")
  void readsLegacyState() {
    // given
    Checkpoint saved =
        new Checkpoint(
            1, "{\"files\":{\"d/s1.jsonl\":{\"offset\":120,\"lines\":2,\"head\":\"abc\"}}}");

    // when
    TranscriptCheckpoint state = TranscriptCheckpoint.read(json, Optional.of(saved));

    // then
    assertThat(state.files())
        .containsExactlyEntriesOf(Map.of("d/s1.jsonl", new FileState(120, 2, "abc")));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          {"files":{"d/s1.jsonl":{"lines":2,"head":"abc"}}} | 0 | 2
          {"files":{"d/s1.jsonl":{"offset":null,"lines":2,"head":"abc"}}} | 0 | 2
          {"files":{"d/s1.jsonl":{"offset":120,"head":"abc"}}} | 120 | 0
          {"files":{"d/s1.jsonl":{"offset":120,"lines":null,"head":"abc"}}} | 120 | 0
          {"files":{"d/s1.jsonl":{"head":"abc"}}} | 0 | 0
          {"files":{"d/s1.jsonl":{"offset":null,"lines":null,"head":"abc"}}} | 0 | 0
          """)
  @DisplayName("숫자가 누락되거나 null인 체크포인트를 읽으면 해당 위치는 0이다")
  void defaultsLegacyNumbers(String valueJson, long offset, long lines) {
    // given
    Checkpoint saved = new Checkpoint(1, valueJson);

    // when
    TranscriptCheckpoint state = TranscriptCheckpoint.read(json, Optional.of(saved));

    // then
    assertThat(state.files())
        .containsExactlyEntriesOf(Map.of("d/s1.jsonl", new FileState(offset, lines, "abc")));
  }

  @Test
  @DisplayName("파일 키를 정렬한 기존 JSON 형식으로 저장한다")
  void writesLegacyState() {
    // given
    TranscriptCheckpoint state =
        new TranscriptCheckpoint(
            Map.of(
                "z/s2.jsonl", new FileState(300, 5, "def"),
                "a/s1.jsonl", new FileState(120, 2, "abc")));

    // when
    Checkpoint saved = state.toCheckpoint(json);

    // then
    assertThat(saved)
        .isEqualTo(
            new Checkpoint(
                1,
                "{\"files\":{\"a/s1.jsonl\":{\"offset\":120,\"lines\":2,\"head\":\"abc\"},\"z/s2.jsonl\":{\"offset\":300,\"lines\":5,\"head\":\"def\"}}}"));
  }
}

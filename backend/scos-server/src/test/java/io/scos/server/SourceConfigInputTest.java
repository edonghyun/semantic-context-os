package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.SourceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * `source add --config`(ADR-0022 §5): TS와 같은 설정 파일(`{"schemaVersion":n,"value":…}`) 경로만 받는다. 옛 Java
 * 형식의 인라인 값 JSON은 받지 않고 파일을 쓰라고 거부한다 — 등록 형식은 TS와 하나다(§2 접두사 없는 ref와 같은 판단).
 */
@DisplayName("source add --config는 TS 설정 파일 경로만 받는다(ADR-0022 §5)")
class SourceConfigInputTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String NOTION_VALUE = "{\"rootPageIds\":[\"p1\"]}";

  @TempDir Path dir;

  Path file(String contents) throws IOException {
    return Files.writeString(dir.resolve("source.json"), contents);
  }

  @Test
  @DisplayName("TS 설정 파일의 schemaVersion과 value를 읽는다")
  void readsTsConfigFile() throws IOException {
    // given
    Path config = file("{\"schemaVersion\":2,\"value\":" + NOTION_VALUE + "}");

    // when
    SourceConfig read = SourceConfigInput.load(JSON, config.toString());

    // then
    assertThat(read).isEqualTo(new SourceConfig(2, NOTION_VALUE));
  }

  @Test
  @DisplayName("{로 시작하는 값(옛 인라인 형식)은 설정 파일을 쓰라고 거부한다")
  void rejectsInlineJson() {
    // when·then
    assertThatThrownBy(() -> SourceConfigInput.load(JSON, NOTION_VALUE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "--config takes a source configuration file path ({\"schemaVersion\":1,\"value\":…});"
                + " inline JSON is not accepted");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "[]",
        "{\"value\":{}}",
        "{\"schemaVersion\":0,\"value\":{}}",
        "{\"schemaVersion\":1.5,\"value\":{}}",
        "{\"schemaVersion\":1}",
        "not json"
      })
  @DisplayName("형식이 맞지 않는 설정 파일은 경로를 담아 거부한다")
  void rejectsMalformedFile(String contents) throws IOException {
    // given
    Path config = file(contents);

    // when·then
    assertThatThrownBy(() -> SourceConfigInput.load(JSON, config.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid source configuration file: " + config);
  }

  @Test
  @DisplayName("없는 설정 파일은 경로를 담아 거부한다")
  void rejectsMissingFile() {
    // given
    Path missing = dir.resolve("missing.json");

    // when·then
    assertThatThrownBy(() -> SourceConfigInput.load(JSON, missing.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Source configuration file could not be read: " + missing);
  }
}

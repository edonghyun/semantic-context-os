package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("출처 분류는 provenance.uri에서 정해진다")
class SourceKindTest {

  @ParameterizedTest(name = "{0} → {1}")
  @DisplayName("작업 기록 출처는 새 분류로 나온다")
  @CsvSource({
    "claude-transcript://d/s1, transcript",
    "claude-memory://d/m.md, memory",
    "file:///opt/x/a.md, document",
    "git://scos/8b36e33a, git",
    "fixture:doc-1, unknown"
  })
  void workRecordKinds(String uri, String wire) {
    // given
    // (uri와 기대 분류는 CsvSource)

    // when
    String actual = SourceKind.of(uri).wire();

    // then
    assertThat(actual).isEqualTo(wire);
  }
}

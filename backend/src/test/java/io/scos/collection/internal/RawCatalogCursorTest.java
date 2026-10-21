package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 원본 카탈로그 커서(TS encodeRawItemCursor). 밀리초 시각의 TS 바이트 동등은 조회 CLI 비교(ops)가 본다. */
@DisplayName("원본 카탈로그 커서는 TS encodeRawItemCursor와 같은 base64url이다")
class RawCatalogCursorTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("jira-main");

  @Test
  @DisplayName("밀리초보다 정밀한 시각도 잃지 않고 되읽힌다")
  void keepsMicroseconds() {
    // given
    var cursor =
        new RawCatalogCursor(Instant.parse("2026-10-16T01:02:03.456789Z"), SOURCE, "issue:1");

    // when
    RawCatalogCursor decoded = RawCatalogCursor.decode(cursor.encode());

    // then
    assertThat(decoded).isEqualTo(cursor);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"@@@", "e30", "WyJ4IiwiYSIsImIiXQ"})
  @DisplayName("base64url·세 문자열 배열·ISO 시각이 아니면 거부한다")
  void rejectsUnreadable(String cursor) {
    // when·then
    assertThatThrownBy(() -> RawCatalogCursor.decode(cursor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Invalid raw catalog cursor");
  }

  @Test
  @DisplayName("커서는 패딩 없는 base64url이다")
  void unpaddedBase64Url() {
    // given: 47바이트 JSON이라 패딩을 붙이면 '='가 하나 붙고, "?b>"는 base64url에서 '-'가 된다
    var cursor = new RawCatalogCursor(Instant.parse("2026-10-16T01:02:03.456Z"), SOURCE, "a?b>");

    // when
    String encoded = cursor.encode();

    // then: ["2026-10-16T01:02:03.456Z","jira-main","a?b>"]를 coreutils base64 + tr '+/' '-_'로 만든
    // 값에서 '='를 뗐다
    assertThat(encoded)
        .isEqualTo("WyIyMDI2LTEwLTE2VDAxOjAyOjAzLjQ1NloiLCJqaXJhLW1haW4iLCJhP2I-Il0");
  }
}

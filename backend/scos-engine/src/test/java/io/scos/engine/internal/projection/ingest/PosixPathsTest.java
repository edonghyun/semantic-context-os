package io.scos.engine.internal.projection.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 링크 정규화가 기대는 Node `path.posix` 의미. 기대값은 Node `path.posix.dirname·join·normalize`의 결과를 손으로 옮겼다. */
@DisplayName("POSIX 경로 함수는 Node path.posix와 같은 값을 낸다")
class PosixPathsTest {
  @ParameterizedTest(name = "dirname(\"{0}\") = \"{1}\"")
  @CsvSource(
      value = {
        "''|.",
        "/|/",
        "a|.",
        "a/|.",
        "/a|/",
        "/a/|/",
        "a/b|a",
        "a/b/|a",
        "a/b//|a",
        "/a/b|/a",
        "a//b|a/",
        "//a|//",
        "a/b/c.md|a/b"
      },
      delimiter = '|')
  @DisplayName("dirname은 마지막 경로 조각을 뗀다(끝 슬래시는 무시, 뿌리는 남긴다)")
  void dirname(String path, String expected) {
    // when·then
    assertThat(PosixPaths.dirname(path)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "normalize(\"{0}\") = \"{1}\"")
  @CsvSource(
      value = {
        "''|.",
        ".|.",
        "./|./",
        "/|/",
        "a/./b|a/b",
        "a//b|a/b",
        "a/../b|b",
        "a/..|.",
        "a/../|./",
        "a/|a/",
        "../a|../a",
        "../../a|../../a",
        "a/../../b|../b",
        "/../a|/a",
        "/a/..|/",
        "//a//b/|/a/b/"
      },
      delimiter = '|')
  @DisplayName("normalize는 .·..·겹친 슬래시를 풀고 절대 경로와 끝 슬래시를 지킨다")
  void normalize(String path, String expected) {
    // when·then
    assertThat(PosixPaths.normalize(path)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "join({0}) = \"{1}\"")
  @CsvSource(
      value = {"a,b|a/b", "a,,b|a/b", ",|.", "a/b,../c|a/c", "/a,b/|/a/b/"},
      delimiter = '|')
  @DisplayName("join은 빈 조각을 빼고 이어 normalize한다")
  void join(String parts, String expected) {
    // when·then
    assertThat(PosixPaths.join(parts.split(",", -1))).isEqualTo(expected);
  }
}

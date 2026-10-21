package io.scos.harness.o2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.harness.o2.ComparisonQuery.Format;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 비교 질의 입력: 질의 로그 JSONL과 판정셋(judgment pack) JSON. 질의는 모두 합성이다. */
@DisplayName("비교 질의는 질의 로그와 판정셋에서 원문을 숨긴 채 읽힌다(O2)")
class ComparisonQueryTest {
  static final String QUERY_LOG =
      """
      {"ts":"2026-10-16T00:00:00.000Z","query":"정산 배치 재시도","topDocumentIds":["d1"],"topScore":0.5}

      {"ts":"2026-10-16T00:00:01.000Z","query":"백업 주기","topDocumentIds":[],"topScore":0}
      """;

  static final String PACK =
      """
      {"name":"synthetic","version":1,"generatedBy":"test","docs":[{"id":"d1","title":"t","body":"b"}],
       "queries":[{"query":"정산 배치 재시도","expectedDocumentIds":["d1","d2"],"slice":"s","evidenceText":"e"}]}
      """;

  @TempDir Path dir;

  Path file(String content) throws Exception {
    return Files.writeString(dir.resolve("queries"), content);
  }

  @Test
  @DisplayName("질의 로그는 줄마다 query를 읽는다")
  void readsQueryLog() throws Exception {
    // when
    List<ComparisonQuery> queries = ComparisonQuery.load(file(QUERY_LOG), Format.AUTO);

    // then
    assertThat(queries).extracting(ComparisonQuery::text).containsExactly("정산 배치 재시도", "백업 주기");
  }

  @Test
  @DisplayName("질의 로그의 빈 줄은 질의가 아니다")
  void skipsBlankLines() throws Exception {
    // when
    List<ComparisonQuery> queries = ComparisonQuery.load(file(QUERY_LOG), Format.AUTO);

    // then
    assertThat(queries).hasSize(2);
  }

  @Test
  @DisplayName("판정셋은 질의와 정답 문서를 읽는다")
  void readsPack() throws Exception {
    // when
    List<ComparisonQuery> queries = ComparisonQuery.load(file(PACK), Format.AUTO);

    // then
    assertThat(queries)
        .containsExactly(new ComparisonQuery("정산 배치 재시도", Optional.of(List.of("d1", "d2"))));
  }

  @Nested
  @DisplayName("질의 로그 한 줄이 JSON이 아니면")
  class Malformed {
    Path input;

    @BeforeEach
    void givenMalformedLine() throws Exception {
      input = file(QUERY_LOG + "{\"query\": 비밀 질의 \n");
    }

    @Test
    @DisplayName("오류는 줄 번호를 밝힌다")
    void namesLine() {
      // when
      Throwable failure = catchThrowable(() -> ComparisonQuery.load(input, Format.QUERY_LOG));

      // then
      assertThat(failure).hasMessageContaining("line 4");
    }

    @Test
    @DisplayName("오류에 질의 원문이 없다")
    void omitsQueryText() {
      // when
      Throwable failure = catchThrowable(() -> ComparisonQuery.load(input, Format.QUERY_LOG));

      // then
      assertThat(failure).hasMessageNotContaining("비밀");
    }
  }

  @Test
  @DisplayName("해시는 질의 sha256의 앞 16자다")
  void hashIsShortDigest() {
    // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad(FIPS 180-2
    // 예제)
    // when
    String hash = new ComparisonQuery("abc", Optional.empty()).hash();

    // then
    assertThat(hash).isEqualTo("ba7816bf8f01cfea");
  }

  @Test
  @DisplayName("기간 필터는 기록 시각이 [since, until)인 질의만 메모리에서 거른다")
  void filtersByLoggedAt() throws Exception {
    // given
    List<ComparisonQuery> queries = ComparisonQuery.load(file(QUERY_LOG), Format.AUTO);

    // when
    List<ComparisonQuery> second =
        ComparisonQuery.between(
            queries,
            Optional.of(Instant.parse("2026-10-16T00:00:01Z")),
            Optional.of(Instant.parse("2026-10-17T00:00:00Z")));

    // then
    assertThat(second).extracting(ComparisonQuery::text).containsExactly("백업 주기");
  }

  @Test
  @DisplayName("기록 시각이 없는 입력(pack)에 기간을 주면 사용법 오류다")
  void filterNeedsTimestamps() throws Exception {
    // given
    List<ComparisonQuery> pack = ComparisonQuery.load(file(PACK), Format.AUTO);

    // when·then
    assertThatThrownBy(
            () -> ComparisonQuery.between(pack, Optional.of(Instant.EPOCH), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

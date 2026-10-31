package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore.Range;
import io.scos.store.postgres.KeywordPlan.Level;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("무제한 범위의 읽기는 접근 필터 도입 전 SQL과 바인드를 유지한다(설계 §4.3)")
class UnrestrictedSqlGoldenTest {
  private static final SearchFilter ACTIVE =
      new SearchFilter(false, null, AccessScope.singleUser());
  private static final SpaceId SPACE = new SpaceId("s1");
  private static final int SEARCH_LIMIT = 10;
  private static final int FILTERED_CHARACTER_LIMIT = 3;
  private static final int FILTERED_WORD_LIMIT = 5;
  private static final int RARE_BOUND = 100;
  private static final Map<String, Integer> RARE_DF = Map.of("정산", 10);
  private static final List<String> UNIT_IDS = List.of("u2", "u1");
  private static final List<String> DOCUMENT_IDS = List.of("d2", "d1");
  private static final Range PAGE = new Range(2, 3);
  private static final String HEADER = "-- 무제한 범위의 SQL — 0.2 접근 필터 뒤에도 바뀌면 안 된다(설계 §4.3)\n";

  @Test
  @DisplayName("덮인 2글자는 필터 없는 포스팅 SQL과 바인드를 유지한다")
  void coveredTwoCharacters() throws IOException {
    // when
    var statement = keyword(Level.RARE, SEARCH_LIMIT, ACTIVE, "정산");

    // then
    assertThat(statement.sql()).isEqualTo(golden("keyword-covered-two", "sql"));
    assertThat(positionalBinds(statement.binds()))
        .isEqualTo(golden("keyword-covered-two", "binds"));
  }

  @Test
  @DisplayName("덮인 1글자는 보관 포함·partition SQL과 바인드를 유지한다")
  void coveredCharacterWithPartitions() throws IOException {
    // given
    var filter = new SearchFilter(true, List.of("p1", "p2"), AccessScope.singleUser());
    // when
    var statement = keyword(Level.EXACT, FILTERED_CHARACTER_LIMIT, filter, "가");

    // then
    assertThat(statement.sql()).isEqualTo(golden("keyword-covered-character", "sql"));
    assertThat(positionalBinds(statement.binds()))
        .isEqualTo(golden("keyword-covered-character", "binds"));
  }

  @Test
  @DisplayName("덮이지 않은 토큰은 후보 재채점 SQL과 바인드를 유지한다")
  void uncoveredToken() throws IOException {
    // when
    var statement = keyword(Level.RARE, SEARCH_LIMIT, ACTIVE, "정산", "가");

    // then
    assertThat(statement.sql()).isEqualTo(golden("keyword-uncovered", "sql"));
    assertThat(positionalBinds(statement.binds())).isEqualTo(golden("keyword-uncovered", "binds"));
  }

  @Test
  @DisplayName("덮이지 않은 4글자는 partition SQL과 바인드를 유지한다")
  void uncoveredWordWithPartitions() throws IOException {
    // given
    var filter = new SearchFilter(false, List.of("p1"), AccessScope.singleUser());
    // when
    var statement = keyword(Level.RARE, FILTERED_WORD_LIMIT, filter, "정산일자");

    // then
    assertThat(statement.sql()).isEqualTo(golden("keyword-uncovered-word", "sql"));
    assertThat(positionalBinds(statement.binds()))
        .isEqualTo(golden("keyword-uncovered-word", "binds"));
  }

  @Test
  @DisplayName("벡터 검색은 보관 제외·전체 partition SQL과 바인드를 유지한다")
  void vectorWithoutArchivedOrPartitions() throws IOException {
    // when
    var statement = VectorSearchSql.search(SPACE, queryVector(), SEARCH_LIMIT, ACTIVE);

    // then
    assertThat(statement.sql()).isEqualTo(golden("vector-active-all", "sql"));
    assertThat(namedBinds(statement.params())).isEqualTo(golden("vector-active-all", "binds"));
  }

  @Test
  @DisplayName("벡터 검색은 보관 포함·전체 partition SQL과 바인드를 유지한다")
  void vectorWithArchivedWithoutPartitions() throws IOException {
    // given
    var filter = new SearchFilter(true, null, AccessScope.singleUser());
    // when
    var statement = VectorSearchSql.search(SPACE, queryVector(), SEARCH_LIMIT, filter);

    // then
    assertThat(statement.sql()).isEqualTo(golden("vector-archived-all", "sql"));
    assertThat(namedBinds(statement.params())).isEqualTo(golden("vector-archived-all", "binds"));
  }

  @Test
  @DisplayName("벡터 검색은 보관 제외·partition 지정 SQL과 바인드를 유지한다")
  void vectorWithoutArchivedWithPartitions() throws IOException {
    // given
    var filter = new SearchFilter(false, List.of("p2", "p1"), AccessScope.singleUser());
    // when
    var statement = VectorSearchSql.search(SPACE, queryVector(), SEARCH_LIMIT, filter);

    // then
    assertThat(statement.sql()).isEqualTo(golden("vector-active-partitions", "sql"));
    assertThat(namedBinds(statement.params()))
        .isEqualTo(golden("vector-active-partitions", "binds"));
  }

  @Test
  @DisplayName("벡터 검색은 보관 포함·partition 지정 SQL과 바인드를 유지한다")
  void vectorWithArchivedAndPartitions() throws IOException {
    // given
    var filter = new SearchFilter(true, List.of("p2", "p1"), AccessScope.singleUser());
    // when
    var statement = VectorSearchSql.search(SPACE, queryVector(), SEARCH_LIMIT, filter);

    // then
    assertThat(statement.sql()).isEqualTo(golden("vector-archived-partitions", "sql"));
    assertThat(namedBinds(statement.params()))
        .isEqualTo(golden("vector-archived-partitions", "binds"));
  }

  @Test
  @DisplayName("벡터 일괄 조회는 SQL과 요청 id 순서의 바인드를 유지한다")
  void vectorsOf() throws IOException {
    // when
    var statement = VectorSearchSql.vectorsOf(SPACE, AccessScope.singleUser(), UNIT_IDS);

    // then
    assertThat(statement.sql()).isEqualTo(golden("vectors-of", "sql"));
    assertThat(namedBinds(statement.params())).isEqualTo(golden("vectors-of", "binds"));
  }

  @Test
  @DisplayName("유닛 일괄 조회는 전체 열 SQL과 요청 id 바인드를 유지한다")
  void getMany() throws IOException {
    // when
    var statement = UnitReadSql.getMany(SPACE, AccessScope.singleUser(), UNIT_IDS);

    // then
    assertThat(statement.sql()).isEqualTo(golden("units-get-many", "sql"));
    assertThat(namedBinds(statement.params())).isEqualTo(golden("units-get-many", "binds"));
  }

  @Test
  @DisplayName("문서 유닛 조회는 SQL과 offset·limit 바인드를 유지한다")
  void listByDocument() throws IOException {
    // when
    var statement = UnitReadSql.listByDocument(SPACE, AccessScope.singleUser(), "d1", PAGE);

    // then
    assertThat(statement.sql()).isEqualTo(golden("units-list-by-document", "sql"));
    assertThat(namedBinds(statement.params())).isEqualTo(golden("units-list-by-document", "binds"));
  }

  @Test
  @DisplayName("링크 대상 조회는 SQL과 시드 id 바인드를 유지한다")
  void linkedUnits() throws IOException {
    // when
    var statement = UnitReadSql.linkedUnits(SPACE, AccessScope.singleUser(), UNIT_IDS);

    // then
    assertThat(statement.sql()).isEqualTo(golden("units-linked", "sql"));
    assertThat(namedBinds(statement.params())).isEqualTo(golden("units-linked", "binds"));
  }

  @Test
  @DisplayName("문서 대표 정보 조회는 SQL과 문서 id 바인드를 유지한다")
  void getDocuments() throws IOException {
    // when
    var statement = UnitReadSql.getDocuments(SPACE, AccessScope.singleUser(), DOCUMENT_IDS);

    // then
    assertThat(statement.sql()).isEqualTo(golden("units-documents", "sql"));
    assertThat(namedBinds(statement.params())).isEqualTo(golden("units-documents", "binds"));
  }

  @Test
  @DisplayName("최신 수집 시각 조회는 C 정렬 SQL과 공간 바인드를 유지한다")
  void latestCapturedAt() throws IOException {
    // when
    var statement = UnitReadSql.latestCapturedAt(SPACE);

    // then
    assertThat(statement.sql()).isEqualTo(golden("units-latest-captured-at", "sql"));
    assertThat(namedBinds(statement.params()))
        .isEqualTo(golden("units-latest-captured-at", "binds"));
  }

  private double[] queryVector() {
    return new double[] {1.0, 0.5, -0.25};
  }

  private String namedBinds(Map<String, Object> params) {
    return params.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + "=" + bindValue(entry.getValue()))
        .collect(Collectors.joining("\n", "", "\n"));
  }

  private KeywordStageQuery.Statement keyword(
      Level level, int limit, SearchFilter filter, String... tokens) {
    return new KeywordStageQuery(
            SPACE, KeywordPlan.of(List.of(tokens), RARE_DF, RARE_BOUND), level, limit, filter)
        .statement();
  }

  private String positionalBinds(List<Object> binds) {
    return IntStream.range(0, binds.size())
        .mapToObj(i -> (i + 1) + "=" + bindValue(binds.get(i)))
        .collect(Collectors.joining("\n", "", "\n"));
  }

  private String bindValue(Object value) {
    String text = value instanceof String[] array ? Arrays.toString(array) : value.toString();
    return value.getClass().getSimpleName() + ":" + text;
  }

  private String golden(String name, String extension) throws IOException {
    String resource = "unrestricted-sql/" + name + "." + extension;
    try (InputStream stream = getClass().getResourceAsStream(resource)) {
      if (stream == null) throw new IllegalStateException("given 준비 실패: 골든 없음: " + resource);
      String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      if (!text.startsWith(HEADER))
        throw new IllegalStateException("given 준비 실패: 골든 보존 주석 없음: " + resource);
      return text.substring(HEADER.length());
    }
  }
}

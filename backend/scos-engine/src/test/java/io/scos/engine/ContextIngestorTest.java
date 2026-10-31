package io.scos.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("공개 인제스터는 문서를 저장하고 삭제를 반영한다(A3)")
class ContextIngestorTest {
  final KeywordOnlyScenario scenario = new KeywordOnlyScenario();

  @AfterEach
  void closeScenario() {
    scenario.close();
  }

  @Test
  @DisplayName("넣은 문서 수를 보고한다")
  void reportsIngestedDocuments() {
    // given: 문서 하나

    // when
    IngestReport report = scenario.ingest();

    // then
    assertThat(report.documents()).isEqualTo(1);
  }

  @Test
  @DisplayName("넣은 유닛 하나를 보고한다")
  void reportsIngestedUnits() {
    // given: 문서 하나

    // when
    IngestReport report = scenario.ingest();

    // then
    assertThat(report.units()).isEqualTo(1);
  }

  @Test
  @DisplayName("정상 문서의 투영은 실패를 보고하지 않는다")
  void reportsNoFailures() {
    // given: 문서 하나

    // when
    IngestReport report = scenario.ingest();

    // then
    assertThat(report.failures()).isEmpty();
  }

  @Nested
  @DisplayName("문서가 투영된 상태에서")
  class DocumentIngested {
    @BeforeEach
    void givenDocumentIngested() {
      scenario.givenDocumentIngested();
    }

    @Test
    @DisplayName("공개 투영기로 넣은 문서를 엔진이 찾는다")
    void ingestedDocumentIsSearchable() {
      // when
      SearchResult result = scenario.search();

      // then
      assertThat(result.hits()).extracting(ContextHit::documentId).containsExactly("d1");
    }

    @Test
    @DisplayName("같은 문서를 다시 넣으면 키워드 색인을 다시 쓰지 않는다")
    void reingestDoesNotWriteKeywords() {
      // given
      if (scenario.keywordUpserts() != 1)
        throw new IllegalStateException("given 준비 실패: 첫 투영이 키워드를 한 번 써야 한다");

      // when
      scenario.ingest();

      // then
      assertThat(scenario.keywordUpserts()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 문서를 다시 넣으면 유닛을 늘리지 않는다")
    void reingestDoesNotDuplicateUnits() {
      // when
      scenario.ingest();

      // then
      assertThat(scenario.storedUnits()).isEqualTo(1);
    }

    @Test
    @DisplayName("현존 목록에서 빠진 문서는 지운다")
    void prunesMissingDocuments() {
      // when
      IngestReport report = scenario.ingest(Set.of());

      // then
      assertThat(report.deleted()).isEqualTo(1);
      assertThat(scenario.documentIds()).isEmpty();
    }
  }

  @ParameterizedTest
  @DisplayName("필수 포트 없이 build하면 그 이름의 IllegalStateException이다")
  @ValueSource(strings = {"maintenance", "keywords", "vectors", "meta", "embedder"})
  void requiresPorts(String missing) {
    // given
    ContextIngestorBuilder builder = scenario.withoutIngestorPort(missing);

    // when
    ThrowingCallable build = builder::build;

    // then
    assertThatThrownBy(build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(missing + " is required");
  }
}

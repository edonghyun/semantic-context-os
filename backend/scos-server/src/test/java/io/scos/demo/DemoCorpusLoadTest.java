package io.scos.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * 데모 코퍼스는 Quickstart 경로(해싱 임베딩 → `import` → search_context 레시피)로 PostgreSQL에 들어가고 검색된다. 적재는 비싸서 한
 * 번만 하고 계약별 테스트가 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("데모 코퍼스는 적재되고 판정 질의로 검색된다")
class DemoCorpusLoadTest {
  static final String DUPLICATE_CHARGE_QUESTION = "결제 중복 승인 장애 원인";

  /** 그 장애의 포스트모템과 근본 원인 이슈. */
  static final List<String> DUPLICATE_CHARGE_ROOT_CAUSE =
      List.of("notion/postmortem-2026-09-07-duplicate-charges", "jira/OTC-311");

  static final int TOP = 5;

  /**
   * 해싱 임베더(철자 겹침)로 상위 5 안에 정답을 찾는 판정 질의 수의 하한(2026-10-18 측정 17/18). 놓치는 1건은 영어 원문을 한국어로 바꿔 말한
   * 질의("애플 실리콘" ↔ "Apple silicon")다 — 의미 임베더가 메울 몫이다. 줄면 검색 회귀다.
   */
  static final int HASHING_HIT_FLOOR = 17;

  final DemoCorpus corpus = DemoCorpus.read(DemoCorpus.defaultRoot());
  DemoCorpusDatabase db;

  @BeforeAll
  void load() {
    db = new DemoCorpusDatabase(corpus);
  }

  /** 질의의 정답 문서 중 하나라도 상위 {@link #TOP} 안에 있는가. */
  boolean hitsWithinTop(JsonNode query) {
    List<String> top = db.topDocuments(query.get("query").asString(), TOP);
    return StreamSupport.stream(query.get("expectedDocumentIds").spliterator(), false)
        .anyMatch(id -> top.contains(id.asString()));
  }

  @Test
  @DisplayName("코퍼스의 문서가 모두 들어간다")
  void loadsEveryDocument() {
    // then
    assertThat(db.documents()).isEqualTo(corpus.documents().size());
  }

  @Test
  @DisplayName("모든 유닛이 벡터 색인에도 들어간다")
  void indexesEveryVector() {
    // then
    assertThat(db.imported().vectors()).isEqualTo(corpus.units().size());
  }

  @Test
  @DisplayName("결제 중복 승인 질의의 상위 2는 포스트모템과 근본 원인 이슈다")
  void findsDuplicateChargeRootCause() {
    // when
    List<String> top = db.topDocuments(DUPLICATE_CHARGE_QUESTION, 2);

    // then
    assertThat(top).containsExactlyInAnyOrderElementsOf(DUPLICATE_CHARGE_ROOT_CAUSE);
  }

  @Test
  @DisplayName("판정 질의는 해싱 플로어 이상 정답을 상위 5 안에 찾는다")
  void judgmentsHitAboveHashingFloor() {
    // when
    long hits =
        StreamSupport.stream(corpus.queries().spliterator(), false)
            .filter(this::hitsWithinTop)
            .count();

    // then
    assertThat(hits).isGreaterThanOrEqualTo(HASHING_HIT_FLOOR);
  }
}

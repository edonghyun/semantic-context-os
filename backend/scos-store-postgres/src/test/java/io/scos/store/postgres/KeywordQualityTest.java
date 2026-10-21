package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.support.RankingMetrics;
import io.scos.support.RankingMetrics.Judged;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * P3 품질 판정(측정 전에 고정한 기준): 제품 키워드 어댑터를 P-4 판정 세트(TS 인제스천 2,012유닛·질의 45)로 재고 TS 어휘 전용 기준선과 비교한다. 기준
 * 1: 그룹별 recall@5·MRR ≥ TS − 0.05.
 */
@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P-4 판정 세트에서 제품 bigram 어댑터는 그룹마다")
class KeywordQualityTest {
  static final SpaceId S = new SpaceId("legacy");
  static final Path P4 =
      Path.of(
          System.getProperty(
              "scos.p4.dir", "scos-store-postgres/src/test/resources/keyword-quality"));

  static final int K = 5;
  static final double TOLERANCE = 0.05;

  /** 그룹별 (제품 어댑터, TS 기준선) 지표. */
  record GroupMetrics(RankingMetrics java, RankingMetrics ts) {}

  final Map<String, GroupMetrics> metricsByGroup = new HashMap<>();

  /** 판정 세트 2,012유닛을 적재하고 세 그룹의 질의를 한 번씩 돌려 지표만 기록한다. */
  @BeforeAll
  void judgeAllGroups() throws Exception {
    ObjectMapper m = new ObjectMapper();
    JsonNode ref = m.readTree(Files.readString(P4.resolve("ts-reference.json")));
    JsonNode spec = m.readTree(Files.readString(P4.resolve("queries.json")));
    KeywordIndex index = givenJudgementUnitsIndexed(ref);
    for (String group : List.of("golden", "short2", "compact")) {
      List<Judged> java = new ArrayList<>(), ts = new ArrayList<>();
      for (JsonNode q : spec.get(group)) {
        String query = q.get("query").asString();
        List<String> expected = new ArrayList<>();
        q.get("expected").forEach(x -> expected.add(x.asString()));
        List<String> docs = new ArrayList<>();
        for (ScoredRef h : index.search(S, query, 50, SearchFilter.NONE))
          if (!docs.contains(h.documentId())) docs.add(h.documentId());
        java.add(new Judged(query, docs, expected));
        List<String> tsDocs = new ArrayList<>();
        for (JsonNode h : ref.get("lexical").get(query))
          if (!tsDocs.contains(h.get("documentId").asString()))
            tsDocs.add(h.get("documentId").asString());
        ts.add(new Judged(query, tsDocs, expected));
      }
      metricsByGroup.put(
          group, new GroupMetrics(RankingMetrics.of(java, K), RankingMetrics.of(ts, K)));
    }
  }

  static KeywordIndex givenJudgementUnitsIndexed(JsonNode ref) {
    PostgresSupport.truncateAll();
    KeywordIndex index = new PostgresKeywordIndex(JdbcClient.create(PostgresSupport.dataSource()));
    List<IndexedText> units = new ArrayList<>();
    for (JsonNode u : ref.get("units"))
      units.add(
          new IndexedText(
              u.get("id").asString(),
              u.get("documentId").asString(),
              u.get("title").asString(),
              u.get("content").asString(),
              u.get("partition").asString(),
              "archived".equals(u.get("tier").asString())));
    index.upsert(S, units);
    return index;
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"golden", "short2", "compact"})
  @DisplayName("recall@5가 TS 기준선 − 0.05 이상이다")
  void recallWithinTolerance(String group) {
    // when
    GroupMetrics metrics = metricsByGroup.get(group);

    // then
    assertThat(metrics.java().recallAtK())
        .as("misses %s", metrics.java().misses())
        .isGreaterThanOrEqualTo(metrics.ts().recallAtK() - TOLERANCE);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"golden", "short2", "compact"})
  @DisplayName("MRR이 TS 기준선 − 0.05 이상이다")
  void mrrWithinTolerance(String group) {
    // when
    GroupMetrics metrics = metricsByGroup.get(group);

    // then
    assertThat(metrics.java().mrr()).isGreaterThanOrEqualTo(metrics.ts().mrr() - TOLERANCE);
  }
}

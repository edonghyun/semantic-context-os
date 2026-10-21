package io.scos.store.postgres;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M-11 적재 처리량 측정(ADR-0020 §5): 유닛 저장·키워드 색인·벡터 색인 upsert에 N건을 한 배치로 넣는 시간을 재어 로그로 남긴다(F3 T3.1 전후
 * 비교의 같은 프로브 — 연결은 DriverManagerDataSource라 트랜잭션 밖 문장마다 새 연결이다). 게이트가 아니다 — 수치는 VERIFICATION "성능"에
 * 기록한다. 운영 규모·운영 적재 경로(풀·트랜잭션)는 SearchScaleProbeTest가 잰다. 단언은 정확성(행 수)뿐. 태그 perf: dbTest에서 빠지고
 * `./gradlew perfTest`로만 돈다.
 */
@Tag("db")
@Tag("perf")
@DisplayName("적재 처리량 프로브는 500건 upsert 시간을 기록한다(ADR-0020 §5)")
class IngestThroughputProbeTest {
  static final Logger log = LoggerFactory.getLogger(IngestThroughputProbeTest.class);
  static final SpaceId S = new SpaceId("probe");
  static final int N = 500;
  static final int DIM = 768;

  DataSource ds = PostgresSupport.dataSource();
  UnitStore units = PostgresTestAdapters.unitStore(ds);
  KeywordIndex keywords = PostgresTestAdapters.keywordIndex(ds);
  VectorIndex vectors = PostgresTestAdapters.vectorIndex(ds);

  /** 같은 유닛 N건의 저장·키워드·벡터 입력. */
  record ProbeBatch(
      List<ContextUnit> units, List<IndexedText> texts, List<IndexedUnit> embeddings) {}

  /** 첫 적재의 단계별 시간과, 같은 내용을 다시 적재(투영 재실행 경로)한 전체 시간(ms). */
  record ProbeTimings(long unitStoreMs, long keywordMs, long vectorMs, long reupsertAllMs) {}

  static ProbeBatch givenProbeBatch() {
    PostgresSupport.truncateAll();
    List<ContextUnit> batch = new ArrayList<>();
    List<IndexedText> texts = new ArrayList<>();
    List<IndexedUnit> embeddings = new ArrayList<>();
    for (int i = 0; i < N; i++) {
      ContextUnit u =
          unit("u-" + i)
              .inDocument("d-" + (i / 10))
              .content("정산 규정 " + i + " 배포 절차 온보딩 가이드 결제 장애 기록")
              .tokens(20)
              .build();
      batch.add(u);
      texts.add(
          new IndexedText(u.id(), u.documentId(), u.title(), u.content(), "/knowledge", false));
      double[] v = new double[DIM];
      v[i % DIM] = 1;
      embeddings.add(new IndexedUnit(u.id(), u.documentId(), "/knowledge", false, v));
    }
    return new ProbeBatch(batch, texts, embeddings);
  }

  ProbeTimings upsertTwice(ProbeBatch batch) {
    long t0 = System.nanoTime();
    units.upsert(S, batch.units());
    long t1 = System.nanoTime();
    keywords.upsert(S, batch.texts());
    long t2 = System.nanoTime();
    vectors.upsert(S, batch.embeddings());
    long t3 = System.nanoTime();
    units.upsert(S, batch.units());
    keywords.upsert(S, batch.texts());
    vectors.upsert(S, batch.embeddings());
    long t4 = System.nanoTime();
    return new ProbeTimings(millis(t1 - t0), millis(t2 - t1), millis(t3 - t2), millis(t4 - t3));
  }

  static long millis(long nanos) {
    return nanos / 1_000_000;
  }

  @Test
  @DisplayName("유닛 500건의 upsert 시간을 기록한다(측정, 게이트 아님)")
  void measuresRowWiseUpsert() {
    // given
    ProbeBatch batch = givenProbeBatch();

    // when
    ProbeTimings timings = upsertTwice(batch);

    // then
    log.info("RESULT ingest units={} {}", N, timings);
    assertThat(units.listAll(S)).as("전량 적재").hasSize(N);
  }
}

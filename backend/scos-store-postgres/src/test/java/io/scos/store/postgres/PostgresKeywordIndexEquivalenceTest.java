package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 드문 bigram 구동(ADR-0014 P-4 부록)이 옛 SQL({@link LegacyKeywordSearch}, 오라클)과 (unit_id, document_id,
 * score) 목록을 같은 순서로, 점수는 비트까지 같게 내는지 시드 고정 무작위 코퍼스·질의로 본다. 어휘를 좁혀(숫자·한글·영문·LIKE 특수 문자) 동점·흔한
 * bigram·붙여쓰기·1글자 토큰이 자주 나오게 하고, 드묾 상한을 여러 값으로 바꿔 1단계 정확·1단계 확정·2단계 재조회가 모두 돌게 한다.
 */
@Tag("db")
@DisplayName("드문 bigram 구동 검색은 옛 SQL과 같은 결과를 같은 순서로 낸다")
class PostgresKeywordIndexEquivalenceTest {
  static final Logger log = LoggerFactory.getLogger(PostgresKeywordIndexEquivalenceTest.class);
  static final SpaceId A = new SpaceId("a");
  static final SpaceId B = new SpaceId("b");
  static final int CORPORA = 16;
  static final int UNITS = 300;
  static final int OTHER_SPACE_UNITS = 40;
  static final int QUERIES_PER_CORPUS = 60;
  static final int[] RARE_LIMITS = {0, 1, 3, 12, 60, Integer.MAX_VALUE - 1};
  static final int[] KS = {1, 2, 5, 20, 400};

  /** 앞쪽일수록 자주 뽑힌다(가중 = 순위의 역수) — "2"·"0"·"정"이 흔한 bigram을 만든다. */
  static final String[] ALPHABET = {
    "2", "0", "정", "산", "데", "이", "터", "6", "년", "기", "준", "a", "p", "i", "1", "%", "_", "\\", "😀"
  };

  static final String[] SEPARATORS = {" ", " ", " ", "", "", "\n", "  "};
  static final String[] PARTITIONS = {"/", "/a", "/b"};

  final SingleConnectionDataSource ds =
      new SingleConnectionDataSource(
          PostgresSupport.jdbcUrl(), PostgresSupport.username(), PostgresSupport.password(), true);
  final JdbcClient jdbc = JdbcClient.create(ds);
  final LegacyKeywordSearch oracle = new LegacyKeywordSearch(jdbc, true);

  @AfterEach
  void close() {
    ds.destroy();
    PostgresSupport.truncateAll();
  }

  /** 한 질의·상한의 불일치 기록. */
  record Mismatch(
      long seed,
      String query,
      int k,
      SearchFilter filter,
      int rareLimit,
      List<ScoredRef> expected,
      List<ScoredRef> got) {}

  /** 비교 결과: 불일치 목록과 답이 나온 단계(상한으로 확정했으면 "+상한")별 질의 수. */
  record Comparison(List<Mismatch> mismatches, Map<String, Integer> stages) {}

  @Test
  @DisplayName("무작위 코퍼스와 질의에서 결과·순서·점수 비트가 모두 같다")
  void matchesLegacySqlOnRandomCorpora() {
    // when
    Comparison c = compareOnRandomCorpora();

    // then
    assertThat(c.stages())
        .as("상한 0인 RARE, 상한으로 확정한 RARE·EXACT, ALL 모두에서 답이 나와야 등가 표본이다")
        .containsKeys("RARE", "RARE+상한", "EXACT+상한", "ALL");
    assertThat(c.mismatches()).isEmpty();
  }

  Comparison compareOnRandomCorpora() {
    List<Mismatch> mismatches = new ArrayList<>();
    Map<String, Integer> stages = new TreeMap<>();
    for (int corpus = 0; corpus < CORPORA; corpus++) {
      long seed = 20261018L + corpus;
      SplittableRandom r = new SplittableRandom(seed);
      PostgresSupport.truncateAll();
      List<IndexedText> units = givenCorpus(r);
      for (int q = 0; q < QUERIES_PER_CORPUS; q++) {
        String query = query(r, units);
        int k = KS[r.nextInt(KS.length)];
        SearchFilter filter = filter(r);
        List<ScoredRef> expected = oracle.search(A, query, k, filter);
        for (int limit : RARE_LIMITS) {
          PostgresKeywordIndex.Answer got =
              new PostgresKeywordIndex(jdbc, limit, limit).answer(A, query, k, filter);
          stages.merge(got.level() + (got.bound() > 0 ? "+상한" : ""), 1, Integer::sum);
          if (!got.refs().equals(expected))
            mismatches.add(new Mismatch(seed, query, k, filter, limit, expected, got.refs()));
        }
      }
    }
    log.info("RESULT equivalence queries={} stages={}", CORPORA * QUERIES_PER_CORPUS, stages);
    return new Comparison(mismatches, stages);
  }

  /** 공간 A의 유닛(반환)과 공간 B의 유닛(격리 확인용 잡음). 10%는 앞선 유닛의 본문을 그대로 쓴다(동점). */
  List<IndexedText> givenCorpus(SplittableRandom r) {
    PostgresKeywordIndex writer = new PostgresKeywordIndex(jdbc, 0, 0);
    List<IndexedText> units = new ArrayList<>();
    for (int i = 0; i < UNITS; i++) {
      boolean copy = i > 0 && r.nextInt(10) == 0;
      IndexedText source = copy ? units.get(r.nextInt(units.size())) : null;
      String title = copy ? source.title() : text(r, r.nextInt(3));
      String content = copy ? source.content() : text(r, 1 + r.nextInt(12));
      units.add(
          new IndexedText(
              "u" + i,
              "d" + i / 7,
              title,
              content,
              PARTITIONS[r.nextInt(PARTITIONS.length)],
              r.nextInt(10) == 0));
    }
    writer.upsert(A, units);
    List<IndexedText> other = new ArrayList<>();
    for (int i = 0; i < OTHER_SPACE_UNITS; i++)
      other.add(new IndexedText("b" + i, "e" + i, "", text(r, 6), "/", false));
    writer.upsert(B, other);
    return units;
  }

  /** 낱말 n개(1–5글자)를 공백·빈 구분자(붙여쓰기)·줄바꿈으로 잇는다. 영문은 가끔 대문자. */
  static String text(SplittableRandom r, int words) {
    StringBuilder sb = new StringBuilder();
    for (int w = 0; w < words; w++) {
      if (w > 0) sb.append(SEPARATORS[r.nextInt(SEPARATORS.length)]);
      sb.append(word(r, 1 + r.nextInt(5)));
    }
    return r.nextInt(5) == 0 ? sb.toString().toUpperCase(Locale.ROOT) : sb.toString();
  }

  static String word(SplittableRandom r, int length) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) sb.append(symbol(r));
    return sb.toString();
  }

  /** 순위 역수 가중 추출. */
  static String symbol(SplittableRandom r) {
    double total = 0;
    for (int i = 0; i < ALPHABET.length; i++) total += 1.0 / (i + 1);
    double x = r.nextDouble() * total;
    for (int i = 0; i < ALPHABET.length; i++) {
      x -= 1.0 / (i + 1);
      if (x < 0) return ALPHABET[i];
    }
    return ALPHABET[ALPHABET.length - 1];
  }

  /** 절반은 유닛 본문의 조각(1–9 코드포인트, 공백을 지우거나 넣어 붙여쓰기·띄어쓰기 질의), 나머지는 무작위 토큰 1–4개. 가끔 같은 토큰을 되풀이한다. */
  static String query(SplittableRandom r, List<IndexedText> units) {
    String q;
    if (r.nextBoolean()) {
      IndexedText u = units.get(r.nextInt(units.size()));
      int[] cps = (u.title() + "\n" + u.content()).codePoints().toArray();
      int from = r.nextInt(cps.length);
      int to = Math.min(cps.length, from + 1 + r.nextInt(9));
      q = new String(cps, from, to - from);
      if (r.nextInt(4) == 0) q = q.replaceAll("\\s+", "");
      else if (r.nextInt(4) == 0 && q.codePointCount(0, q.length()) > 2)
        q =
            q.substring(0, q.offsetByCodePoints(0, 2))
                + " "
                + q.substring(q.offsetByCodePoints(0, 2));
    } else {
      StringBuilder sb = new StringBuilder();
      int tokens = 1 + r.nextInt(4);
      for (int t = 0; t < tokens; t++)
        sb.append(t == 0 ? "" : " ").append(word(r, 1 + r.nextInt(6)));
      q = sb.toString();
    }
    if (r.nextInt(8) == 0) q = q + " " + q;
    return q;
  }

  static SearchFilter filter(SplittableRandom r) {
    return switch (r.nextInt(4)) {
      case 0, 1 -> SearchFilter.NONE;
      case 2 -> new SearchFilter(true, null);
      default ->
          new SearchFilter(r.nextBoolean(), r.nextBoolean() ? List.of("/a") : List.of("/a", "/b"));
    };
  }
}

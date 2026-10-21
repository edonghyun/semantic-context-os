package io.scos.store.postgres;

import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.VectorIndex;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TS 합성 fixture(`/fixture/ts-reference.json`, 해싱 64차원)의 벡터와 `searchVectors` 기대 결과. 어댑터에 같은 벡터를 넣고
 * 같은 질의를 돌려 TS 결과와 비교한다(ADR-0014 P-3).
 */
final class TsVectorReference {
  /**
   * pgvector `vector`는 float4 저장이라 TS가 1 ULP 차이로 가른 동점군 안의 자리가 바뀔 수 있다. TS 점수 차가 이 값 이하인 hit끼리는
   * 동점군이다.
   */
  static final double TIE = 1e-12;

  private static final int TOP_K = 50;
  private static final SearchFilter INCLUDE_ARCHIVED = new SearchFilter(true, null);

  /** 질의 하나의 TS 기대 hit와 어댑터 결과. */
  record QueryComparison(
      String query, List<ScoredRef> got, List<String> wantIds, List<Double> wantScores) {
    boolean sameHitCount() {
      return got.size() == wantIds.size();
    }

    /** 자리가 다른 hit 중 TS 동점군 안의 교환으로 설명되지 않는 수. */
    int mismatchesOutsideTies() {
      int mismatches = 0;
      for (int i = 0; i < Math.min(got.size(), wantIds.size()); i++) {
        String id = got.get(i).unitId();
        if (id.equals(wantIds.get(i))) continue;
        int j = wantIds.indexOf(id);
        if (j < 0 || Math.abs(wantScores.get(j) - wantScores.get(i)) > TIE) mismatches++;
      }
      return mismatches;
    }

    double maxRelativeError() {
      double max = 0;
      for (int i = 0; i < Math.min(got.size(), wantScores.size()); i++) {
        double want = wantScores.get(i);
        max =
            Math.max(
                max,
                Math.abs(got.get(i).score() - want) / Math.max(Math.abs(want), Double.MIN_NORMAL));
      }
      return max;
    }
  }

  /** 모든 질의의 비교 — 테스트는 이 집계만 본다. */
  record Comparison(List<QueryComparison> queries) {
    int comparedHits() {
      return queries.stream().mapToInt(q -> q.got().size()).sum();
    }

    List<String> queriesWithDifferentHitCount() {
      return queries.stream().filter(q -> !q.sameHitCount()).map(QueryComparison::query).toList();
    }

    int mismatchesOutsideTies() {
      return queries.stream().mapToInt(QueryComparison::mismatchesOutsideTies).sum();
    }

    double maxRelativeError() {
      return queries.stream().mapToDouble(QueryComparison::maxRelativeError).max().orElse(0);
    }
  }

  private final JsonNode reference;

  private TsVectorReference(JsonNode reference) {
    this.reference = reference;
  }

  static TsVectorReference load() {
    try (InputStream in =
        TsVectorReference.class.getResourceAsStream("/fixture/ts-reference.json")) {
      return new TsVectorReference(JsonMapper.builder().build().readTree(in));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** fixture의 벡터를 그대로 넣는다(id의 "-숫자" 꼬리를 뗀 것이 문서, fx-archived*는 archived). */
  void indexInto(VectorIndex index) {
    List<IndexedUnit> units = new ArrayList<>();
    for (JsonNode row : reference.get("vectors")) {
      byte[] bytes = HexFormat.of().parseHex(row.get("hex").asString());
      double[] v = new double[bytes.length / 8];
      ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(v);
      String id = row.get("id").asString();
      units.add(
          new IndexedUnit(
              id, id.replaceAll("-\\d+$", ""), "/knowledge", id.startsWith("fx-archived"), v));
    }
    index.upsert(SpaceId.LEGACY, units);
  }

  /** 모든 fixture 질의를 어댑터로 돌려 TS 기대와 짝짓는다. */
  Comparison compareWith(VectorIndex index) {
    List<QueryComparison> comparisons = new ArrayList<>();
    for (var entry : reference.get("vector").properties()) {
      JsonNode q = entry.getValue().get("query");
      double[] query = new double[q.size()];
      for (int i = 0; i < query.length; i++) query[i] = q.get(i).asDouble();
      List<String> wantIds = new ArrayList<>();
      List<Double> wantScores = new ArrayList<>();
      for (JsonNode h : entry.getValue().get("hits")) {
        wantIds.add(h.get("unitId").asString());
        wantScores.add(h.get("score").asDouble());
      }
      List<ScoredRef> got =
          index.search(SpaceId.LEGACY, List.of(query), TOP_K, INCLUDE_ARCHIVED).get(0);
      comparisons.add(new QueryComparison(entry.getKey(), got, wantIds, wantScores));
    }
    return new Comparison(comparisons);
  }
}

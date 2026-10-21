package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 임베딩 캐시 포트 계약(F9, ADR-0026): (세대, 입력 키) → 벡터, 있는 키는 덮어쓰지 않는다. 값은 float4로도 정확한 수만 쓴다. */
@DisplayName("임베딩 캐시는 세대·입력 키마다 처음 벡터를 둔다(ADR-0026)")
public abstract class EmbeddingCacheContract {
  protected static final String GENERATION = "GeminiEmbedder:3:title+aliases+content";
  protected static final String OTHER_GENERATION = "GeminiEmbedder:4:title+aliases+content";

  /** 입력 키는 캐시에 불투명한 문자열이다. 값은 엔진이 만드는 모양(sha256 16진수 — "제목\n정산 본문"·"제목\n환불 본문")을 손으로 적었다. */
  protected static final String KEY_A =
      "507f82e141ab0546c3bb16f081247eaa5bba251be0962eaf84e6ae11d61e82b4";

  protected static final String KEY_B =
      "3a5bbc924e123be2aababe527d44ccc03f922a7dee3751c6262a5bee0a984b75";
  protected static final double[] VECTOR_A = {0.5, -0.25, 2};
  protected static final double[] VECTOR_B = {1, 0, -1};

  protected EmbeddingCache cache;

  protected abstract EmbeddingCache newCache();

  @BeforeEach
  void setUpContract() {
    cache = newCache();
  }

  @Nested
  @DisplayName("한 세대에 KEY_A의 벡터 A를 넣은 상태에서")
  class AfterPut {
    @BeforeEach
    void givenStored() {
      cache.putIfAbsent(GENERATION, Map.of(KEY_A, VECTOR_A));
    }

    @Test
    @DisplayName("같은 세대·같은 키로 찾으면 벡터 A다")
    void findsStoredVector() {
      // when
      Map<String, double[]> found = cache.find(GENERATION, List.of(KEY_A));

      // then
      assertThat(found.get(KEY_A)).containsExactly(VECTOR_A);
    }

    @Test
    @DisplayName("다른 세대로는 찾지 못한다")
    void otherGenerationMisses() {
      // when
      Map<String, double[]> found = cache.find(OTHER_GENERATION, List.of(KEY_A));

      // then
      assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("없는 키는 결과에서 빠진다")
    void absentKeysAreOmitted() {
      // when
      Map<String, double[]> found = cache.find(GENERATION, List.of(KEY_A, KEY_B));

      // then
      assertThat(found).containsOnlyKeys(KEY_A);
    }

    @Test
    @DisplayName("있는 키에 다시 넣어도 처음 벡터가 남는다")
    void keepsFirstVector() {
      // when
      cache.putIfAbsent(GENERATION, Map.of(KEY_A, VECTOR_B));

      // then
      assertThat(cache.find(GENERATION, List.of(KEY_A)).get(KEY_A)).containsExactly(VECTOR_A);
    }

    @Test
    @DisplayName("있는 키와 새 키를 함께 넣으면 새로 넣은 키의 수를 돌려준다")
    void countsInserted() {
      // when
      int inserted = cache.putIfAbsent(GENERATION, Map.of(KEY_A, VECTOR_B, KEY_B, VECTOR_B));

      // then
      assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("키 목록이 비면 빈 결과다")
    void emptyLookup() {
      // when
      Map<String, double[]> found = cache.find(GENERATION, List.of());

      // then
      assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("비운 뒤에는 찾는 키가 없다")
    void clearEmpties() {
      // when
      cache.clear();

      // then
      assertThat(cache.find(GENERATION, List.of(KEY_A))).isEmpty();
    }

    @Test
    @DisplayName("비운 뒤에 새로 넣은 키를 찾는다")
    void putAfterClear() {
      // given
      cache.clear();

      // when
      cache.putIfAbsent(GENERATION, Map.of(KEY_A, VECTOR_B));

      // then
      assertThat(cache.find(GENERATION, List.of(KEY_A))).containsOnlyKeys(KEY_A);
    }
  }

  @Test
  @DisplayName("크기는 모든 세대의 키 수다")
  void sizeCountsEveryGeneration() {
    // given
    cache.putIfAbsent(GENERATION, Map.of(KEY_A, VECTOR_A, KEY_B, VECTOR_B));
    cache.putIfAbsent(OTHER_GENERATION, Map.of(KEY_A, VECTOR_A));

    // when
    int size = cache.size();

    // then
    assertThat(size).isEqualTo(3);
  }

  @Test
  @DisplayName("비우면 지운 키 수를 돌려준다")
  void clearReturnsDeleted() {
    // given
    cache.putIfAbsent(GENERATION, Map.of(KEY_A, VECTOR_A, KEY_B, VECTOR_B));

    // when
    int deleted = cache.clear();

    // then
    assertThat(deleted).isEqualTo(2);
  }
}

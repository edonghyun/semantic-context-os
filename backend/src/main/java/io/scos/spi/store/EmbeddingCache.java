package io.scos.spi.store;

import java.util.Collection;
import java.util.Map;

/**
 * 임베딩 캐시 포트(F9, ADR-0026): (임베딩 세대, sha256(임베딩 입력)) → 벡터. TS 엔진에서 가져온 벡터를 재수집 투영이 같은 입력이면 다시 쓴다 — 그
 * 입력은 Gemini에 보내지 않는다. 세대가 다르면(차원·입력 형식) 다른 키라 찾지 못한다. 계약은 {@code EmbeddingCacheContract}.
 */
public interface EmbeddingCache {
  /** 이 세대에서 찾은 키만 돌려준다(없는 키는 생략). */
  Map<String, double[]> find(String generation, Collection<String> inputKeys);

  /** 없는 키만 넣고(있으면 처음 값 유지) 새로 넣은 수를 돌려준다. 호출자의 트랜잭션에 참여한다. */
  int putIfAbsent(String generation, Map<String, double[]> entries);

  /** 모든 세대의 키 수. */
  int size();

  /** 캐시를 비우고 지운 키 수를 돌려준다 — 재수집이 끝난 뒤 정리(ADR-0026 §2 "수명"). */
  int clear();

  /** 캐시 없음(찾는 것이 없고 넣지 않는다) — 캐시를 쓰지 않는 조립·테스트. */
  EmbeddingCache NONE =
      new EmbeddingCache() {
        @Override
        public Map<String, double[]> find(String generation, Collection<String> inputKeys) {
          return Map.of();
        }

        @Override
        public int putIfAbsent(String generation, Map<String, double[]> entries) {
          return 0;
        }

        @Override
        public int size() {
          return 0;
        }

        @Override
        public int clear() {
          return 0;
        }
      };
}

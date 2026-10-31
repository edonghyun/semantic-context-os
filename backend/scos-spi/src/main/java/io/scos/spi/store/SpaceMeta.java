package io.scos.spi.store;

import java.util.Optional;

/** 공간별 키-값 메타 포트(G6·G9): 임베딩 세대·소스 커서. 공간 밖은 보이지 않는다. */
public interface SpaceMeta {
  Optional<String> get(SpaceId space, String key);

  void set(SpaceId space, String key, String value);

  /**
   * 트랜잭션 안에서 읽고 그 트랜잭션이 끝날 때까지 다른 쓰기를 막는다(stage2 §1.5.4) — 쓰기 직전의 세대 확인이 커밋까지 유효하다. 트랜잭션이 없는 구현은
   * {@link #get}과 같다.
   */
  default Optional<String> getLocked(SpaceId space, String key) {
    return get(space, key);
  }

  /** 값이 없을 때만 쓰고, 그 뒤의 값을 돌려준다 — 동시에 처음 쓰려는 쪽 가운데 먼저 기록한 값만 남는다. */
  String putIfAbsent(SpaceId space, String key, String value);
}

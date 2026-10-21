package io.scos.spi.store;

import java.util.Optional;

/** 공간별 키-값 메타 포트(TS MetaPort, G6·G9): 임베딩 세대·소스 커서. 공간 밖은 보이지 않는다. */
public interface SpaceMeta {
  Optional<String> get(SpaceId space, String key);

  void set(SpaceId space, String key, String value);
}

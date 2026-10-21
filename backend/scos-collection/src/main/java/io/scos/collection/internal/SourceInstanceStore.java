package io.scos.collection.internal;

import io.scos.spi.connector.SourceInstanceId;
import java.util.List;
import java.util.Optional;

/** 소스 등록 저장 포트. */
public interface SourceInstanceStore {
  void save(SourceInstance instance);

  Optional<SourceInstance> get(SourceInstanceId id);

  /** enabled 소스를 id 코드 유닛 순으로(TS는 localeCompare — 감독 순서만 영향). */
  List<SourceInstance> listEnabled();
}

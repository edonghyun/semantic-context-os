package io.scos.collection.internal;

import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.connector.SourceInstanceId;
import java.util.List;
import java.util.Optional;

/** 소스 등록 저장 포트. */
public interface SourceInstanceStore {
  void save(SourceInstance instance);

  Optional<SourceInstance> get(SourceInstanceId id);

  /** 호출자 트랜잭션에서 행을 잠그고 kind·config·credentialRef가 캡처한 설정과 같은지 확인한다. */
  void requireCollectionConfiguration(SourceDescriptor expected);

  /** enabled 소스를 id 코드 유닛 순으로(TS는 localeCompare — 감독 순서만 영향). */
  List<SourceInstance> listEnabled();
}

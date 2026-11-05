package io.scos.collection.internal;

import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceInstanceId;
import java.util.List;
import java.util.Optional;

/** 소스 등록 저장 포트. */
public interface SourceInstanceStore {
  void save(SourceInstance instance);

  /** 최근 투영에서 소유 충돌로 건너뛴 문서 수. 0으로 회복한 결과도 기록한다. */
  void recordOwnershipSkipped(SourceInstanceId source, int count);

  int ownershipSkipped(SourceInstanceId source);

  Optional<SourceInstance> get(SourceInstanceId id);

  /** 호출자 트랜잭션에서 행을 잠그고 kind·config·credentialRef가 캡처한 설정과 같은지 확인한다. */
  void requireCollectionConfiguration(SourceDescriptor expected);

  /** 비활성 소스도 포함한 전체 목록. 만료 처리는 수집 활성 상태와 독립이다. */
  List<SourceInstance> listAll();

  /** enabled 소스를 id 코드 유닛 순으로(TS는 localeCompare — 감독 순서만 영향). */
  List<SourceInstance> listEnabled();
}

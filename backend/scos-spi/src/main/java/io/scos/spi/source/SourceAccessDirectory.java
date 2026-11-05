package io.scos.spi.source;

import java.util.Map;

/** 원천 그룹·신원 원장의 쓰기 경계. 병합 정책과 소스별 직렬화는 호출자가 소유한다. */
public interface SourceAccessDirectory {
  record Snapshot(Map<String, SourceIdentityRecord> identities, Map<String, GroupSnapshot> groups) {
    public Snapshot {
      identities = Map.copyOf(identities);
      groups = Map.copyOf(groups);
    }

    public static Snapshot empty() {
      return new Snapshot(Map.of(), Map.of());
    }
  }

  /** 호출자 트랜잭션에서 현재 행을 잠근다. 관측한 행이 없으면 빈 스냅샷이다. */
  Snapshot current(SourceInstanceId source);

  /** 주어진 행을 저장한다. 누락한 행·다른 원천은 유지한다. 빈 스냅샷은 무변경이다. 저장 오류는 unchecked 예외다. */
  void save(SourceInstanceId source, Snapshot snapshot);
}

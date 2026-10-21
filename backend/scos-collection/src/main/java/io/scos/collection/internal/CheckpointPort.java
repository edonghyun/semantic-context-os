package io.scos.collection.internal;

import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;

/**
 * 체크포인트 포트(불변식 2). 진행 위치는 {@link #advance}로만, 그리고 호출자의 트랜잭션 안에서 원본과 함께만 전진한다. 계약은 {@code
 * CheckpointContract}가 정의한다.
 */
public interface CheckpointPort {
  StoredCheckpoint read(SourceInstanceId source);

  /**
   * 저장된 revision이 {@code expectedRevision}일 때만 {@code next}를 쓰고 revision+1을 돌려준다. 아니면 {@link
   * CheckpointConflictException}. 호출자의 트랜잭션이 롤백되면 전진도 취소된다.
   */
  long advance(SourceInstanceId source, long expectedRevision, Checkpoint next, Instant now);
}

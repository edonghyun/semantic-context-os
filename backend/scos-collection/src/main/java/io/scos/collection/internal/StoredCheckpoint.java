package io.scos.collection.internal;

import io.scos.spi.connector.Checkpoint;
import java.util.Optional;

/** 저장된 체크포인트와 revision. 저장된 적이 없으면 revision 0, 체크포인트 없음. */
public record StoredCheckpoint(long revision, Optional<Checkpoint> checkpoint) {
  public static StoredCheckpoint none() {
    return new StoredCheckpoint(0, Optional.empty());
  }
}

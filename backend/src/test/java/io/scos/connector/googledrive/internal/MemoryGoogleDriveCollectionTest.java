package io.scos.connector.googledrive.internal;

import io.scos.collection.internal.MemoryCollectionRuntime;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.memory.MemoryCheckpointPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.connector.googledrive.GoogleDriveAdapter;
import io.scos.engine.support.text.MutableClock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;

/**
 * {@link GoogleDriveCollectionContract}의 메모리 포트 판(TESTING §3.6 5층, 공용 `MemoryCollectionRuntime` —
 * 재시도 대기는 기다리지 않는다). 같은 런타임으로 두 번 수집하므로 run id를 실행마다 바꾼다.
 */
@DisplayName("Drive 수집 계약 — 메모리 포트(§3.6 5층)")
class MemoryGoogleDriveCollectionTest extends GoogleDriveCollectionContract {
  @Override
  protected Assembled newRuntime(GoogleDriveAdapter adapter, MutableClock clock) {
    MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
    MemoryRawItemStore rawItems = new MemoryRawItemStore();
    MemoryCheckpointPort checkpoints = new MemoryCheckpointPort();
    AtomicInteger runs = new AtomicInteger();
    return new Assembled(
        MemoryCollectionRuntime.over(
                clock, sources, new MemoryWorkClaimPort(), new MemoryRunStore())
            .rawItems(rawItems)
            .checkpoints(checkpoints)
            .adapter(adapter)
            .runIds(() -> new RunId("run-" + runs.incrementAndGet()))
            .build(),
        sources,
        rawItems,
        checkpoints);
  }
}

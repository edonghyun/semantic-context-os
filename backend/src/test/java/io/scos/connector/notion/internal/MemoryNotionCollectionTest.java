package io.scos.connector.notion.internal;

import io.scos.collection.internal.MemoryCollectionRuntime;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.memory.MemoryCheckpointPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.connector.notion.NotionAdapter;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.text.MutableClock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;

/**
 * {@link NotionCollectionContract}의 메모리 포트 판(TESTING §3.6 5층, 공용 `MemoryCollectionRuntime` — 재시도
 * 대기는 기다리지 않는다). 같은 런타임으로 여러 번 수집하므로 run id를 실행마다 바꾼다.
 */
@DisplayName("Notion roots 수집 계약 — 메모리 포트(§3.6 5층)")
class MemoryNotionCollectionTest extends NotionCollectionContract {
  @Override
  protected Assembled newRuntime(
      NotionAdapter adapter, MutableClock clock, ProviderCallExecutor calls) {
    MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
    MemoryRawItemStore rawItems = new MemoryRawItemStore();
    MemoryRunStore runStore = new MemoryRunStore();
    MemoryCheckpointPort checkpoints = new MemoryCheckpointPort();
    MemorySnapshotGuardStore guards = new MemorySnapshotGuardStore();
    AtomicInteger runs = new AtomicInteger();
    return new Assembled(
        MemoryCollectionRuntime.over(clock, sources, new MemoryWorkClaimPort(), runStore)
            .rawItems(rawItems)
            .checkpoints(checkpoints)
            .guards(guards)
            .adapter(adapter)
            .calls(calls)
            .runIds(() -> new RunId("run-" + runs.incrementAndGet()))
            .build(),
        sources,
        rawItems,
        runStore,
        checkpoints,
        guards);
  }
}

package io.scos.store.memory;

import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.store.SpaceId;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 조회와 삭제를 함께 시작해 확인-후-조회 경합을 표본화한다. 정확한 스레드 교차 순서를 보장하지는 않는다. */
final class AclReadDeleteScenario {
  // sleep·무작위 지연 없이 두 연산이 겹치는 구간을 반복해서 관측한다.
  private static final int ATTEMPTS = 100_000;
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);
  private static final int PARTICIPANTS = 2;
  private final CyclicBarrier start = new CyclicBarrier(PARTICIPANTS);
  private final AclStore store;
  private final SpaceId space;
  private final List<DocumentAcl> acls;
  private final List<String> documentIds;

  AclReadDeleteScenario(AclStore store, SpaceId space, DocumentAcl acl) {
    this.store = store;
    this.space = space;
    acls = List.of(acl);
    documentIds = List.of(acl.documentId());
  }

  void readDuringDeletes() throws Exception {
    try (var worker = Executors.newSingleThreadExecutor()) {
      var deleting = worker.submit(this::replaceAndDelete);
      start.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
      for (int attempt = 0; attempt < ATTEMPTS; attempt++) store.get(space, documentIds);
      deleting.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  private Void replaceAndDelete() throws Exception {
    start.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      store.put(space, acls);
      store.delete(space, documentIds);
    }
    return null;
  }
}

package io.scos.boardarchive.store;

import io.scos.boardarchive.fetch.FetchLog;
import io.scos.spi.access.SourceFreshness;
import io.scos.spi.access.SourceFreshnessContract;
import io.scos.spi.source.SourceInstanceId;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("보관소의 원천 최신성은 사이트별 마지막 성공 수집 완료 시각이다(B9)")
class ArchiveSourceFreshnessTest extends SourceFreshnessContract {
  private static final Duration RUN_LENGTH = Duration.ofMinutes(5);
  private static final RunStats STATS = new RunStats(0, 0, 0, List.of(), false);
  @TempDir Path dir;
  private ArchiveStore store;

  @Override
  protected SourceFreshness newFreshness() {
    store = ArchiveStore.open(dir.resolve("archive.db"));
    return store;
  }

  @Override
  protected void recordSuccess(SourceInstanceId instance, Instant finishedAt) {
    finish(instance, finishedAt, Optional.empty());
  }

  @Override
  protected void recordNonSuccess(SourceInstanceId instance, Instant finishedAt) {
    finish(instance, finishedAt, Optional.of("TransportFailureException"));
  }

  private void finish(SourceInstanceId site, Instant finishedAt, Optional<String> failure) {
    long run = store.startRun(site.value(), finishedAt.minus(RUN_LENGTH));
    store.finishRun(run, STATS, new FetchLog(), failure, finishedAt);
  }

  @AfterEach
  void close() {
    store.close();
  }
}

package io.scos.client.source;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.client.collect.LocalContentCapture;
import io.scos.client.collect.StateStore;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.connector.SourceDescriptor;
import io.scos.spi.source.SourceInstanceId;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 로컬 어댑터의 입력은 실제 콘텐츠 캡처와 유효한 소스·호출 실행기를 사용한다. */
public final class SourceAdapterScenario {
  private final SourceAdapter adapter;
  private final Clock clock;
  private Runnable afterCapture = () -> {};
  private final LocalContentCapture capture = new LocalContentCapture();
  private final ContentCapture observingCapture =
      new ContentCapture() {
        @Override
        public RawContent json(String mediaType, String valueJson) {
          RawContent result = capture.json(mediaType, valueJson);
          afterCapture.run();
          return result;
        }

        @Override
        public RawContent bytes(String mediaType, byte[] bytes) {
          RawContent result = capture.bytes(mediaType, bytes);
          afterCapture.run();
          return result;
        }
      };

  public void afterCapture(Runnable observation) {
    afterCapture = observation;
  }

  private final CancellationToken cancel = CancellationToken.none();

  public SourceAdapterScenario(SourceAdapter adapter, Clock clock) {
    this.adapter = adapter;
    this.clock = clock;
  }

  public List<CollectionPage> collect(Optional<Checkpoint> checkpoint) {
    return collect(checkpoint, CollectionMode.INCREMENTAL);
  }

  public List<CollectionPage> collect(Optional<Checkpoint> checkpoint, CollectionMode mode) {
    SourceDescriptor source =
        new SourceDescriptor(
            new SourceInstanceId(adapter.kind()), adapter.kind(), adapter.kind(), config(), "");
    List<CollectionPage> pages = new ArrayList<>();
    adapter.collect(
        new CollectionInput(
            source,
            checkpoint,
            mode,
            ProviderCallExecutor.defaults(clock, ProviderCallMetrics.of(new SimpleMeterRegistry())),
            observingCapture,
            cancel),
        pages::add);
    return pages;
  }

  public SourceConfig config() {
    return new SourceConfig(1, "{}");
  }

  public Optional<Checkpoint> givenStoredCheckpoint(Path file, Checkpoint checkpoint) {
    try (StateStore store = StateStore.open(file)) {
      store.commit(adapter.kind(), new CollectionPage(List.of(), List.of(), checkpoint));
    }
    try (StateStore store = StateStore.open(file)) {
      return store.checkpoint(adapter.kind());
    }
  }
}

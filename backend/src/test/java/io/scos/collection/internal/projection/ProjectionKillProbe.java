package io.scos.collection.internal.projection;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.engine.internal.projection.HashVectors;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitStore;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.ChildJvm;
import io.scos.support.RealTimeScheduler;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 투영 도중 kill -9 프로브. args: jdbcUrl user password markerDir datasetFile phase(run|resume). run: 수집 뒤
 * 투영 — 임베더가 둘째 문서에서 "mid" 마커를 만들고 proceed를 기다린다(부모가 그 사이 kill -9; 첫 문서는 커밋됨). resume: 보통 임베더로 due
 * 투영을 수행하고 RESULT 한 줄.
 */
public final class ProjectionKillProbe {
  static final SpaceId SPACE = PostgresProjection.SPACE;

  static final class GatingEmbedder implements Embedder {
    final Path markers;
    final boolean gate;
    final AtomicInteger calls = new AtomicInteger();

    GatingEmbedder(Path markers, boolean gate) {
      this.markers = markers;
      this.gate = gate;
    }

    @Override
    public int dim() {
      return HashVectors.DIM;
    }

    @Override
    public List<double[]> embed(List<String> texts) {
      if (gate && calls.incrementAndGet() == 2) {
        try {
          ChildJvm.touch(markers.resolve("mid"));
          ChildJvm.awaitMarker(markers.resolve("proceed"), 60_000);
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
      return texts.stream().map(HashVectors::of).toList();
    }
  }

  public static void main(String[] args) throws Exception {
    DataSource ds = new DriverManagerDataSource(args[0], args[1], args[2]);
    Path markers = Path.of(args[3]);
    Path dataset = Path.of(args[4]);
    String phase = args[5];
    var adapters = PostgresCollectionConfiguration.adapters(ds);
    Clock clock = Clock.systemUTC();
    UnitStore units = PostgresTestAdapters.unitStore(ds);
    ProjectionRun projection =
        PostgresProjection.over(ds, clock, new GatingEmbedder(markers, phase.equals("run")))
            .leaseTtl(Duration.ofSeconds(2))
            .run("probe-" + phase, RealTimeScheduler.create());
    if (phase.equals("run")) {
      try (FakeSlackServer server = new FakeSlackServer(dataset)) {
        adapters
            .sources()
            .save(
                SlackAssembly.instance(SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP, clock.instant()));
        PostgresCollectionRuntime.over(ds, clock)
            .adapter(SlackAssembly.adapter(server.baseUrl(), server.token(), clock))
            .owner("probe")
            .heartbeats(RealTimeScheduler.create())
            .waitsForReal()
            .build()
            .collect(SlackAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
        ChildJvm.touch(markers.resolve("collected"));
        projection.runDue(CancellationToken.none());
      }
    } else {
      // 죽은 투영의 점유(projection:<source>)가 만료될 때까지 조건 대기(상한은 hang 방지용)
      ChildJvm.awaitCondition(
          "projection lease expired",
          () ->
              adapters
                  .claims()
                  .current(ProjectionRun.projectionKey(SlackAssembly.SOURCE))
                  .map(c -> !c.isActiveAt(clock.instant()))
                  .orElse(true),
          20_000);
      List<ProjectionRun.Report> reports = projection.runDue(CancellationToken.none());
      List<ContextUnit> all = units.listAll(SPACE);
      System.out.println(
          "RESULT resume outcome="
              + (reports.isEmpty() ? "none" : reports.get(0).outcome().wire())
              + " units="
              + all.size()
              + " distinct="
              + all.stream().map(ContextUnit::id).distinct().count()
              + " pending="
              + adapters.outbox().pending().size());
    }
    System.exit(0);
  }
}

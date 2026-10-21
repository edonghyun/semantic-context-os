package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.PageCommit;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.WorkClaim;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.ChildJvm;
import io.scos.support.TestMetrics;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P-2 자식 프로세스. args: jdbcUrl user password markerDir assembly(correct|wrong)
 * phase(run|run-kill-mid|resume). correct: 네 어댑터가 같은 DataSource → Outbox 행이 페이지 커밋과 함께. wrong:
 * Outbox 어댑터가 별도 DataSource로 커밋 뒤에 따로 쓴다(저장소가 갈라졌는데 Outbox 흡수가 없는 잘못된 조립). run은 "committed" 마커 뒤
 * proceed를 기다린다 — 부모가 그 사이 kill -9.
 */
public final class OutboxKillProbe {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");

  public static void main(String[] args) throws Exception {
    DataSource ds = new DriverManagerDataSource(args[0], args[1], args[2]);
    Path markers = Path.of(args[3]);
    String assembly = args[4];
    String phase = args[5];
    JdbcClient jdbc = JdbcClient.create(ds);
    jdbc.sql(
            "CREATE TABLE IF NOT EXISTS probe_units (id TEXT PRIMARY KEY, source_instance_id TEXT NOT NULL)")
        .update();
    var adapters = PostgresCollectionConfiguration.adapters(ds);
    Clock clock = Clock.systemUTC();

    switch (phase) {
      case "run", "run-kill-mid" -> {
        WorkClaim claim =
            adapters
                .claims()
                .tryClaim(SOURCE, new OwnerId("probe"), Duration.ofMinutes(1), clock.instant())
                .orElseThrow();
        List<RawItem> items =
            List.of("i1", "i2", "i3", "i4", "i5", "i6").stream()
                .map(id -> RawItem.minimal(id, "v1", "{}", clock.instant()))
                .toList();
        CollectionPage page =
            CollectionPage.ofItems(items, new Checkpoint(1, "{\"after\":\"i6\"}"));
        RunId run = new RunId("probe-run");
        adapters.runs().start(run, SOURCE, clock.instant());
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        if (assembly.equals("correct")) {
          new PageCommit(
                  tx,
                  adapters.claims(),
                  adapters.runs(),
                  adapters.rawItems(),
                  adapters.checkpoints(),
                  adapters.outbox(),
                  clock,
                  TestMetrics.collection(),
                  adapters.changes(),
                  new Progress(clock))
              .commit(claim, run, 0, page);
          ChildJvm.touch(markers.resolve("committed"));
        } else {
          // 잘못된 조립: Outbox가 다른 DataSource(다른 저장소를 흉내) — 페이지 커밋 뒤 별도 커밋으로 등록된다
          OutboxPort detached =
              PostgresCollectionConfiguration.adapters(
                      new DriverManagerDataSource(args[0], args[1], args[2]))
                  .outbox();
          new PageCommit(
                  tx,
                  adapters.claims(),
                  adapters.runs(),
                  adapters.rawItems(),
                  adapters.checkpoints(),
                  noOutbox(),
                  clock,
                  TestMetrics.collection(),
                  adapters.changes(),
                  new Progress(clock))
              .commit(claim, run, 0, page);
          ChildJvm.touch(markers.resolve("committed"));
          ChildJvm.awaitMarker(markers.resolve("proceed-outbox"), 30_000);
          detached.requestProjection(SOURCE, clock.instant());
        }
        ChildJvm.awaitMarker(markers.resolve("proceed"), 30_000);
        project(jdbc, adapters.outbox(), markers, phase.equals("run-kill-mid"));
        System.out.println("RESULT run-finished");
      }
      case "resume" -> {
        int projected = project(jdbc, adapters.outbox(), markers, false);
        int units = jdbc.sql("SELECT count(*) FROM probe_units").query(Integer.class).single();
        System.out.println(
            "RESULT resume projected="
                + projected
                + " units="
                + units
                + " pending="
                + adapters.outbox().pending().size()
                + " items="
                + adapters.rawItems().count(SOURCE));
      }
      default -> throw new IllegalArgumentException(phase);
    }
  }

  /** 요청된 소스를 전량 재투영(멱등 upsert). killMid면 3개 뒤 "mid" 마커를 만들고 proceed-mid를 기다린다. */
  static int project(JdbcClient jdbc, OutboxPort outbox, Path markers, boolean killMid)
      throws Exception {
    List<SourceInstanceId> requested = outbox.pending();
    for (SourceInstanceId source : requested) {
      List<String> ids =
          jdbc.sql(
                  "SELECT external_id FROM raw_items WHERE source_instance_id = :s ORDER BY external_id")
              .param("s", source.value())
              .query(String.class)
              .list();
      int n = 0;
      for (String id : ids) {
        jdbc.sql(
                "INSERT INTO probe_units (id, source_instance_id) VALUES (:id, :s) ON CONFLICT (id) DO NOTHING")
            .param("id", "unit-" + id)
            .param("s", source.value())
            .update();
        if (++n == 3 && killMid) {
          ChildJvm.touch(markers.resolve("mid"));
          ChildJvm.awaitMarker(markers.resolve("proceed-mid"), 30_000);
        }
      }
      outbox.complete(source, outbox.request(source).orElseThrow().version());
    }
    return requested.size();
  }

  /** 잘못된 조립 흉내: Outbox가 DB 밖(메모리)에 있어 커밋과 함께 남지 않는다. */
  static OutboxPort noOutbox() {
    return new MemoryOutboxPort();
  }
}

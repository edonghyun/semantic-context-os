package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.support.ChildJvm;
import io.scos.support.RealTimeScheduler;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 중단(kill -9) 후 재개 프로브. args: jdbcUrl user password markerDir datasetFile phase(run|resume) owner.
 * run: 가짜 Slack 서버를 띄우고 수집하다가 첫 "cursor=page:1" 요청(스레드 replies 둘째 페이지)에서 게이트에 걸린다(그 전 2페이지는 커밋됨) →
 * "gated" 마커 → 부모가 kill -9. resume: 점유 만료를 조건 대기한 뒤 저장된 체크포인트에서 재개하고 RESULT 한 줄을 찍는다. lease TTL은
 * 실시간 2s. 종류와 무관한 부분(점유 만료 대기, 결과 줄)은 {@link PostgresCollectionObservations}다 — 다른 커넥터의 kill 프로브는
 * 가짜 공급자·소스 설정·게이트 경로만 바꾼다.
 */
public final class CollectionKillProbe {
  /**
   * 죽이는 첫 프로세스(owner "run")의 첫 run. {@link PostgresCollectionRuntime}의 기본 run id 규칙 {@code
   * {owner}-run-{n}}에 기댄다.
   */
  static final RunId DEAD_RUN = new RunId("run-run-1");

  public static void main(String[] args) throws Exception {
    DataSource ds = new DriverManagerDataSource(args[0], args[1], args[2]);
    Path markers = Path.of(args[3]);
    Path dataset = Path.of(args[4]);
    String phase = args[5];
    String owner = args[6];
    Clock clock = Clock.systemUTC();
    var observe = new PostgresCollectionObservations(ds, SlackAssembly.SOURCE, clock);
    try (FakeSlackServer server = new FakeSlackServer(dataset)) {
      if (phase.equals("run")) {
        PostgresCollectionConfiguration.adapters(ds)
            .sources()
            .save(
                SlackAssembly.instance(SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP, clock.instant()));
        CountDownLatch never = new CountDownLatch(1);
        server.gateOn("cursor=page:1", never, path -> touch(markers.resolve("gated")));
      } else {
        // 죽은 worker의 점유가 만료될 때까지 조건 대기(상한은 hang 방지용)
        ChildJvm.awaitCondition("lease expired", observe::leaseExpired, 20_000);
      }
      CollectionRuntime runtime =
          PostgresCollectionRuntime.over(ds, clock)
              .adapter(SlackAssembly.adapter(server.baseUrl(), server.token(), clock))
              .owner(owner)
              .leaseTtl(Duration.ofSeconds(2))
              .heartbeats(RealTimeScheduler.create())
              .waitsForReal()
              .build();
      CollectionRuntime.Report report =
          runtime.collect(
              SlackAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
      System.out.println(observe.probeResult(phase, report, DEAD_RUN));
      if (report.status() == RunStatus.FAILED) System.exit(2);
    }
    System.exit(0);
  }

  private static void touch(Path marker) {
    try {
      ChildJvm.touch(marker);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}

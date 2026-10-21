package io.scos.collection.internal.postgres;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.connector.tiro.internal.FakeTiroServer;
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
 * Tiro 수집 중단(kill -9) 후 재시작 프로브(TESTING §3.6 6층). 종류와 무관한 부분(점유 만료 대기, 결과 줄)은 {@link
 * PostgresCollectionObservations}다. args: jdbcUrl user password markerDir datasetFile
 * phase(seed|run|resume) owner. seed: 게이트 없이 끝까지 수집한다(앞 수집). run: 가짜 Tiro를 띄우고 수집하다가 둘째 목록 페이지
 * 요청(cursor=page:1)에서 게이트에 걸린다(첫 목록 페이지는 커밋됨) → "gated" 마커 → 부모가 kill -9. resume: 점유 만료를 조건 대기한 뒤
 * 수집하고 RESULT 한 줄을 찍는다. lease TTL은 실시간 2s.
 */
public final class TiroCollectionKillProbe {
  /**
   * 죽이는 첫 프로세스(owner "run")의 첫 run. {@link PostgresCollectionRuntime}의 기본 run id 규칙 {@code
   * {owner}-run-{n}}에 기댄다.
   */
  static final RunId DEAD_RUN = new RunId("run-run-1");

  /** 둘째 목록 페이지 요청(문단 둘째 페이지 `size=100&cursor=page:1`과 구분한다). */
  static final String SECOND_LIST_PAGE = "notes?size=20&cursor=page:1";

  public static void main(String[] args) throws Exception {
    DataSource ds = new DriverManagerDataSource(args[0], args[1], args[2]);
    Path markers = Path.of(args[3]);
    Path dataset = Path.of(args[4]);
    String phase = args[5];
    String owner = args[6];
    Clock clock = Clock.systemUTC();
    var observe = new PostgresCollectionObservations(ds, TiroAssembly.SOURCE, clock);
    try (FakeTiroServer server = new FakeTiroServer(dataset)) {
      if (phase.equals("seed") || phase.equals("run")) {
        PostgresCollectionConfiguration.adapters(ds)
            .sources()
            .save(TiroAssembly.instance(server.configJson(), clock.instant()));
        if (phase.equals("run"))
          server.gateOn(
              SECOND_LIST_PAGE, new CountDownLatch(1), path -> touch(markers.resolve("gated")));
      } else {
        // 죽은 worker의 점유가 만료될 때까지 조건 대기(상한은 hang 방지용)
        ChildJvm.awaitCondition("lease expired", observe::leaseExpired, 20_000);
      }
      CollectionRuntime runtime =
          PostgresCollectionRuntime.over(ds, clock)
              .adapter(TiroAssembly.adapter(server.baseUrl(), server.token(), clock))
              .owner(owner)
              .leaseTtl(Duration.ofSeconds(2))
              .heartbeats(RealTimeScheduler.create())
              .waitsForReal()
              .build();
      CollectionRuntime.Report report =
          runtime.collect(
              TiroAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
      // 공용 결과 줄 + Tiro 정리 확인용 tombstone 수
      System.out.println(
          observe.probeResult(phase, report, DEAD_RUN) + " tombstones=" + observe.tombstones());
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

package io.scos.connector.jira.internal;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.postgres.PostgresCollectionObservations;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.connector.jira.JiraAssembly;
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
 * Jira 페이지 중간 kill 뒤 재개 프로브(TESTING §3.6 6층). args: jdbcUrl user password markerDir fixtureDir
 * phase(run|resume). run: 가짜 Jira(TS 데이터셋)를 띄우고 수집하다가 이슈 40005 요청(TS 생성기의 중단 지점 — 40004·40005 배치
 * 안)에서 게이트에 걸린다 → "gated" 마커 → 부모가 kill -9. resume: 점유 만료를 기다려 저장된 체크포인트에서 재개하고 결과 줄을 찍는다. lease
 * TTL 실시간 2s. 종류와 무관한 부분은 {@link PostgresCollectionObservations}다.
 */
public final class JiraCollectionKillProbe {

  public static void main(String[] args) throws Exception {
    DataSource ds = new DriverManagerDataSource(args[0], args[1], args[2]);
    Path markers = Path.of(args[3]);
    System.setProperty("scos.fixtures.jira.dir", args[4]);
    String phase = args[5];
    Clock clock = Clock.systemUTC();
    var observe = new PostgresCollectionObservations(ds, JiraAssembly.SOURCE, clock);
    try (FakeJiraServer jira = new FakeJiraServer(JiraFixtures.dataset())) {
      if (phase.equals("run")) {
        PostgresCollectionConfiguration.adapters(ds)
            .sources()
            .save(JiraAssembly.instance(JiraAssembly.SYNTHETIC_SITE, clock.instant()));
        jira.gateOn(
            JiraTsRuns.crashPath(), new CountDownLatch(1), path -> touch(markers.resolve("gated")));
      } else {
        // 죽은 worker의 점유가 만료될 때까지 조건 대기(상한은 hang 방지용)
        ChildJvm.awaitCondition("lease expired", observe::leaseExpired, 20_000);
      }
      CollectionRuntime runtime =
          PostgresCollectionRuntime.over(ds, clock)
              .adapter(JiraAssembly.adapter(jira.baseUrl(), jira.token(), clock))
              .owner(phase)
              .leaseTtl(Duration.ofSeconds(2))
              .heartbeats(RealTimeScheduler.create())
              .waitsForReal()
              .build();
      CollectionRuntime.Report report =
          runtime.collect(
              JiraAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
      System.out.println(observe.probeResult(phase, report, new RunId("run-run-1")));
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

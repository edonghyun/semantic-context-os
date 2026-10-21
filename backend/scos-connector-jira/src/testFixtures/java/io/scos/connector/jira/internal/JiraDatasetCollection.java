package io.scos.connector.jira.internal;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.connector.jira.JiraAssembly;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.scheduling.TaskScheduler;

/**
 * 다른 패키지의 DB 종단 테스트(투영 종단)가 쓰는 Jira 수집 한 번(TESTING §3.6 6층): DB에 합성 소스를 등록하고 가짜 Jira(TS 데이터셋)를 실제
 * PostgreSQL 런타임으로 끝까지 수집한다. 데이터셋 밖 요청이 있었으면 실패한다. 조립(1층 {@link JiraAssembly})과 수명·런타임을 나눈다.
 */
public final class JiraDatasetCollection {
  private JiraDatasetCollection() {}

  public static CollectionRuntime.Report collectInto(
      DataSource ds, Clock clock, TaskScheduler heartbeats) {
    try (FakeJiraServer jira = new FakeJiraServer(JiraFixtures.dataset())) {
      PostgresCollectionConfiguration.adapters(ds)
          .sources()
          .save(JiraAssembly.instance(JiraAssembly.SYNTHETIC_SITE, clock.instant()));
      return PostgresCollectionRuntime.over(ds, clock)
          .adapter(JiraAssembly.adapter(jira.baseUrl(), jira.token(), clock))
          .heartbeats(heartbeats)
          .build()
          .collect(JiraAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

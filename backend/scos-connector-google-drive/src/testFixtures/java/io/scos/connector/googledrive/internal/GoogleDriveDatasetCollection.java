package io.scos.connector.googledrive.internal;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.scheduling.TaskScheduler;

/**
 * 다른 패키지의 DB 종단 테스트(투영 종단)가 쓰는 Drive 수집 한 번(TESTING §3.6 6층): DB에 합성 소스를 등록하고 가짜 Google(TS 데이터셋)을
 * 실제 PostgreSQL 런타임으로 첫 수집한다. 시계는 데이터셋 기준 시각(TS 생성기와 같은 now)에서 시작한다. 조립(1층 {@link
 * GoogleDriveAssembly})과 수명·런타임을 나눈다.
 */
public final class GoogleDriveDatasetCollection {
  /** 데이터셋 기준 시각(google-drive-dataset.json now). */
  public static final Instant NOW = GoogleDriveScenario.NOW;

  /** TS 기대 파일 디렉터리(harness/fixtures/google-drive). */
  public static final Path FIXTURES = GoogleDriveFixtures.DIR;

  private GoogleDriveDatasetCollection() {}

  public static CollectionRuntime.Report collectInto(
      DataSource ds, MutableClock clock, TaskScheduler heartbeats) {
    try (FakeGoogleServer google = new FakeGoogleServer(GoogleDriveFixtures.dataset())) {
      PostgresCollectionConfiguration.adapters(ds)
          .sources()
          .save(GoogleDriveAssembly.instance(GoogleDriveAssembly.SYNTHETIC_ACCOUNT, NOW));
      return PostgresCollectionRuntime.over(ds, clock)
          .adapter(
              GoogleDriveAssembly.adapter(
                  google.baseUrl(), GoogleDriveAssembly.CREDENTIAL_JSON, clock))
          .heartbeats(heartbeats)
          .build()
          .collect(
              GoogleDriveAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

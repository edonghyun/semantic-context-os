package io.scos.connector.googledrive;

import io.scos.connector.googledrive.internal.GoogleDriveApiClient;
import io.scos.connector.googledrive.internal.GoogleDriveCollection;
import io.scos.connector.googledrive.internal.GoogleDriveConfiguration;
import io.scos.connector.googledrive.internal.GoogleDriveSession;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.DeletionDetection;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import java.time.Clock;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Drive 수집 어댑터(TS GoogleDriveAdapter). 내 파일 전체와 접근 가능한 공유 드라이브를 각각 명시적으로 걷고, 변경 피드(사용자·드라이브별
 * startPageToken)로 삭제·접근 상실을 받는다. 목록에 없다는 것은 삭제의 증거가 아니다 — coverage는 늘 incremental이다(TS
 * deletionDetection "provider_events"). 실제 걷기는 run 하나가 소유하는 {@link GoogleDriveCollection}이 한다.
 */
public final class GoogleDriveAdapter implements SourceAdapter {
  public static final String KIND = "google-drive";

  private final CredentialResolver credentials;
  private final GoogleDriveApiClient client;
  private final ObjectMapper json;
  private final Clock clock;

  public GoogleDriveAdapter(
      CredentialResolver credentials, GoogleDriveApiClient client, ObjectMapper json, Clock clock) {
    this.credentials = credentials;
    this.client = client;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public void validateConfig(SourceConfig config) {
    GoogleDriveConfiguration.read(json, config);
  }

  @Override
  public CollectionCoverage coverage(
      SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
    return CollectionCoverage.INCREMENTAL;
  }

  /** TS deletionDetection "provider_events": 삭제·접근 상실은 변경 피드가 알린다(목록의 부재는 근거가 아니다). */
  @Override
  public DeletionDetection deletionDetection(SourceConfig config) {
    return DeletionDetection.PROVIDER_EVENTS;
  }

  @Override
  public void collect(CollectionInput input, PageSink sink) {
    GoogleDriveConfiguration config = GoogleDriveConfiguration.read(json, input.source().config());
    GoogleDriveSession session =
        client.session(credentials.resolve(input.source().credentialRef()));
    new GoogleDriveCollection(input, config, session, json, clock).collect(sink);
  }
}

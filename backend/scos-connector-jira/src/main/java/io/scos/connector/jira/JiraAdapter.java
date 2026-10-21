package io.scos.connector.jira;

import io.scos.connector.jira.internal.JiraApiClient;
import io.scos.connector.jira.internal.JiraCollection;
import io.scos.connector.jira.internal.JiraConfiguration;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import java.time.Clock;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * Jira 수집 어댑터(TS JiraAdapter, jira-adapter.ts). 매번 사이트 전체를 작업 큐(체크포인트 v1)로 걷는다: 필드·프로젝트·보드·필터·대시보드
 * → 프로젝트마다 컴포넌트·버전·이슈 검색 → 이슈 → 첨부. 권한으로 걸러진 목록은 삭제를 증명할 수 없으므로 없어진 것을 지우지 않고, coverage는 늘
 * incremental이다. 실제 걷기는 run 하나가 소유하는 {@link JiraCollection}이 한다.
 */
public final class JiraAdapter implements SourceAdapter {
  public static final String KIND = "jira";

  private final CredentialResolver credentials;
  private final JiraApiClient client;
  private final ObjectMapper json;
  private final Clock clock;

  public JiraAdapter(
      CredentialResolver credentials, JiraApiClient client, ObjectMapper json, Clock clock) {
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
    JiraConfiguration.read(json, config);
  }

  /** 삭제 감지가 없다(TS deletionDetection "none") — 완료가 삭제 근거가 될 수 없다. */
  @Override
  public CollectionCoverage coverage(
      SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
    return CollectionCoverage.INCREMENTAL;
  }

  @Override
  public void collect(CollectionInput input, PageSink sink) {
    JiraConfiguration config = JiraConfiguration.read(json, input.source().config());
    String token =
        CredentialResolver.headerValue(credentials.resolve(input.source().credentialRef()));
    new JiraCollection(input, config, token, client, json, clock).collect(sink);
  }
}

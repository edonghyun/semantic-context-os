package io.scos.connector.notion;

import io.scos.connector.notion.internal.NotionApiClient;
import io.scos.connector.notion.internal.NotionCalls;
import io.scos.connector.notion.internal.NotionConfiguration;
import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.connector.notion.internal.NotionMutations;
import io.scos.connector.notion.internal.NotionRootCollection;
import io.scos.connector.notion.internal.NotionRootTraversal;
import io.scos.connector.notion.internal.NotionSearchCollection;
import io.scos.connector.notion.internal.NotionSearchState;
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
 * Notion 수집 어댑터(TS NotionAdapter, notion-adapter.ts). 설정에 따라 두 모드다.
 *
 * <ul>
 *   <li>roots(운영 설정): 설정한 루트 페이지의 트리를 작업 큐(체크포인트 v3)로 걷는다({@link NotionRootCollection}). 새 사이클을
 *       처음부터 보는 run만 complete_snapshot이라 보지 못한 원본을 정리할 근거가 된다. discoverSharedPages면 검색도 하므로
 *       incremental이다.
 *   <li>search: 공유된 페이지 전체를 검색으로(체크포인트 v1·v2) — 삭제를 증명할 수 없어 incremental이다.
 * </ul>
 *
 * 체크포인트는 자격 증명을 풀기 전에 읽는다 — 잘못된 체크포인트·바뀐 루트는 공급자 호출 없이 실패한다(TS).
 */
public final class NotionAdapter implements SourceAdapter {
  public static final String KIND = "notion";

  private final CredentialResolver credentials;
  private final NotionApiClient client;
  private final ObjectMapper json;
  private final Clock clock;

  public NotionAdapter(
      CredentialResolver credentials, NotionApiClient client, ObjectMapper json, Clock clock) {
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
    NotionConfiguration.read(json, config);
  }

  /**
   * TS deletionDetection: 설정 수준의 첫 사이클 coverage가 complete_snapshot(roots, discoverSharedPages 아님)이면
   * complete_snapshot, 아니면 none. 소스 목록이 보여 준다.
   */
  @Override
  public DeletionDetection deletionDetection(SourceConfig config) {
    return coverage(config, CollectionMode.INCREMENTAL, Optional.empty())
            == CollectionCoverage.COMPLETE_SNAPSHOT
        ? DeletionDetection.COMPLETE_SNAPSHOT
        : DeletionDetection.NONE;
  }

  /**
   * TS collectionCoverage: 검색 모드·discoverSharedPages는 incremental, roots는 새 사이클을 처음부터 보는 체크포인트일 때만
   * complete_snapshot. 저장된 체크포인트를 읽을 수 없으면(형식 위반·바뀐 루트) TS처럼 여기서 던진다 — 런타임은 run 없이 점유를 풀고
   * 전파한다(CONTRACTS F17-D1).
   */
  @Override
  public CollectionCoverage coverage(
      SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
    if (!(NotionConfiguration.read(json, config) instanceof Roots roots)
        || roots.discoverSharedPages()) return CollectionCoverage.INCREMENTAL;
    return NotionRootTraversal.startsCycle(json, roots, checkpoint)
        ? CollectionCoverage.COMPLETE_SNAPSHOT
        : CollectionCoverage.INCREMENTAL;
  }

  @Override
  public void collect(CollectionInput input, PageSink sink) {
    NotionConfiguration config = NotionConfiguration.read(json, input.source().config());
    switch (config) {
      case Roots roots -> {
        NotionRootTraversal traversal = NotionRootTraversal.resume(json, roots, input.checkpoint());
        String token = bearerToken(input);
        new NotionRootCollection(roots, traversal, client, calls(input), mutations(input), token)
            .collect(sink);
      }
      case NotionConfiguration.Search search -> {
        NotionSearchState state = NotionSearchState.read(json, input.checkpoint());
        String token = bearerToken(input);
        new NotionSearchCollection(state, client, calls(input), mutations(input), token)
            .collect(sink);
      }
    }
  }

  /**
   * Bearer 헤더에 실을 토큰: 줄바꿈·NUL이 든 값은 공급자를 부르기 전에 invalid_configuration(run errorKind "Error")으로 거른다.
   */
  private String bearerToken(CollectionInput input) {
    return CredentialResolver.headerValue(credentials.resolve(input.source().credentialRef()));
  }

  private NotionCalls calls(CollectionInput input) {
    return new NotionCalls(input, clock);
  }

  private NotionMutations mutations(CollectionInput input) {
    return new NotionMutations(input.content(), clock);
  }
}

package io.scos.connector.notion;

import io.scos.connector.notion.internal.NotionApiClient;
import io.scos.connector.notion.internal.NotionCalls;
import io.scos.connector.notion.internal.NotionConfiguration;
import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.connector.notion.internal.NotionMutations;
import io.scos.connector.notion.internal.NotionReconciliation;
import io.scos.connector.notion.internal.NotionReconciliationState;
import io.scos.connector.notion.internal.NotionRootCollection;
import io.scos.connector.notion.internal.NotionRootTraversal;
import io.scos.connector.notion.internal.NotionScopeIdentity;
import io.scos.connector.notion.internal.NotionSearchCollection;
import io.scos.connector.notion.internal.NotionSearchState;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.DeletionDetection;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.SnapshotScope;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import java.time.Clock;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * Notion 수집 어댑터. 설정에 따라 두 모드다.
 *
 * <ul>
 *   <li>roots: v4 계획으로 증분 탐색과 전체 재조정을 교대한다. 시드부터 시작한 전체 run만 부재 재검증 후 정리할 수 있다.
 *       discoverSharedPages는 기존 v3 순회와 검색을 실행하며 incremental이다.
 *   <li>search: 공유된 페이지 전체를 검색으로(체크포인트 v1·v2) — 삭제를 증명할 수 없어 incremental이다.
 * </ul>
 *
 * 체크포인트는 자격 증명을 풀기 전에 읽는다 — 잘못된 체크포인트·바뀐 루트는 공급자 호출 없이 실패한다.
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
   * 설정 수준의 첫 사이클 coverage가 complete_snapshot(roots, discoverSharedPages 아님)이면 complete_snapshot,
   * 아니면 none. 소스 목록이 보여 준다.
   */
  @Override
  public DeletionDetection deletionDetection(SourceConfig config) {
    return coverage(config, CollectionMode.INCREMENTAL, Optional.empty())
            == CollectionCoverage.COMPLETE_SNAPSHOT
        ? DeletionDetection.COMPLETE_SNAPSHOT
        : DeletionDetection.NONE;
  }

  /**
   * 검색 모드·discoverSharedPages는 incremental, roots는 새 사이클을 처음부터 보는 체크포인트일 때만 complete_snapshot. 저장된
   * 체크포인트를 읽을 수 없으면(형식 위반·바뀐 루트) 여기서 던진다 — 런타임은 run 없이 점유를 풀고 전파한다(CONTRACTS F17-D1).
   */
  @Override
  public CollectionCoverage coverage(
      SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
    if (!(NotionConfiguration.read(json, config) instanceof Roots roots)
        || roots.discoverSharedPages()) return CollectionCoverage.INCREMENTAL;
    return NotionReconciliationState.read(json, roots, mode, checkpoint, clock.instant())
        .coverage();
  }

  @Override
  public Optional<SnapshotScope> snapshotVerification(SourceConfig config) {
    if (NotionConfiguration.read(json, config) instanceof Roots roots
        && !roots.discoverSharedPages())
      return Optional.of(NotionScopeIdentity.verificationScope(json, roots));
    return Optional.empty();
  }

  @Override
  public void collect(CollectionInput input, PageSink sink) {
    NotionConfiguration config = NotionConfiguration.read(json, input.source().config());
    switch (config) {
      case Roots roots -> {
        if (!roots.discoverSharedPages()) {
          var state =
              NotionReconciliationState.read(
                  json, roots, input.mode(), input.checkpoint(), clock.instant());
          new NotionReconciliation(
                  roots,
                  state,
                  new NotionReconciliation.Dependencies(client, input, clock, bearerToken(input)))
              .collect(sink);
          return;
        }
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

package io.scos.connector.tiro;

import io.scos.connector.tiro.internal.TiroApiClient;
import io.scos.connector.tiro.internal.TiroCheckpoint;
import io.scos.connector.tiro.internal.TiroCollection;
import io.scos.connector.tiro.internal.TiroConfiguration;
import io.scos.spi.connector.AccessPolicy;
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
 * Tiro 회의록 어댑터(ADR-0022): 워크스페이스 노트 목록(폴더 또는 전체) → 노트마다 상세·전사 문단(전 페이지)·요약·회의 문서를 모두 읽은 뒤
 * `note:<guid>` 원본 하나(`tiro.note`)로 저장한다. 음성·공유 링크는 받지 않는다. 실제 걷기는 run 하나가 소유하는 {@link
 * TiroCollection}이 한다.
 *
 * <ul>
 *   <li>항상 complete_snapshot: 매 실행이 목록 첫 페이지부터 끝까지 걷고(저장된 커서로 재개하지 않는다), 성공하면 이번에 보지 못한 노트를 런타임이
 *       tombstone으로 바꾼다.
 *   <li>노트 상세의 403·404는 그 노트의 삭제다. 문단·요약·문서 경로의 403·404는 그 종류만 비우고 `unavailableResources`에 남긴다. 목록의
 *       403·404는 run 실패다(키·설정 오류와 접근 회수를 구분할 수 없다).
 *   <li>sourceVersion = sha256(JSON.stringify(본문)) — 전사·요약만 바뀌어도 새 버전이다. sourceUpdatedAt은 노트·요약·문서
 *       중 가장 늦은 updatedAt.
 *   <li>잠긴 문단은 거부가 아니다(전부 목록에 있고 본문에서만 빠진다) — 거부 항목이 있으면 정리가 막히므로 메타데이터 `lockedParagraphs`로만 남긴다.
 * </ul>
 */
public final class TiroAdapter implements SourceAdapter {
  public static final String KIND = "tiro";

  private final CredentialResolver credentials;
  private final TiroApiClient client;
  private final ObjectMapper json;
  private final Clock clock;

  public TiroAdapter(
      CredentialResolver credentials, TiroApiClient client, ObjectMapper json, Clock clock) {
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
  public AccessPolicy accessPolicy() {
    return TiroProjector.ACCESS;
  }

  @Override
  public void validateConfig(SourceConfig config) {
    TiroConfiguration.read(json, config);
  }

  /** 재개하지 않으므로 모든 실행이 완전한 목록이다. */
  @Override
  public CollectionCoverage coverage(
      SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
    return CollectionCoverage.COMPLETE_SNAPSHOT;
  }

  @Override
  public DeletionDetection deletionDetection(SourceConfig config) {
    return DeletionDetection.COMPLETE_SNAPSHOT;
  }

  @Override
  public void collect(CollectionInput input, PageSink sink) {
    TiroConfiguration config = TiroConfiguration.read(json, input.source().config());
    TiroCheckpoint.requireSameScope(json, input.checkpoint(), config.scope());
    String token =
        CredentialResolver.headerValue(credentials.resolve(input.source().credentialRef()));
    new TiroCollection(input, config, token, client, json, clock).collect(sink);
  }
}

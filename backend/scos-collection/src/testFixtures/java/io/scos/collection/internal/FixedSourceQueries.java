package io.scos.collection.internal;

import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 소스 목록 응답만 고정하는 읽기 경계 대역. 저장 동작은 없으며 다른 조회는 지원하지 않는다. */
public final class FixedSourceQueries implements SourceQueries {
  private final List<SourceInstance> sources;
  private final Map<SourceInstanceId, AccessStatus> access;
  private final Map<String, AccessExplanation> explanations;

  public FixedSourceQueries(List<SourceInstance> sources) {
    this(sources, Map.of(), Map.of());
  }

  public FixedSourceQueries(
      List<SourceInstance> sources, Map<SourceInstanceId, AccessStatus> access) {
    this(sources, access, Map.of());
  }

  public FixedSourceQueries(
      List<SourceInstance> sources,
      Map<SourceInstanceId, AccessStatus> access,
      Map<String, AccessExplanation> explanations) {
    this.sources = List.copyOf(sources);
    this.access = Map.copyOf(access);
    this.explanations = Map.copyOf(explanations);
  }

  @Override
  public List<SourceInstance> listSources() {
    return sources;
  }

  @Override
  public Optional<SourceStatus> status(SourceInstanceId source, Instant now) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<AccessExplanation> accessExplanation(String documentId) {
    return Optional.ofNullable(explanations.get(documentId));
  }

  @Override
  public Optional<AccessStatus> accessStatus(SourceInstanceId source) {
    return Optional.ofNullable(access.get(source));
  }

  @Override
  public List<RecentRejection> recentRejections(SourceInstanceId source, int limit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public RawCatalogPage rawList(RawCatalogQuery query) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<RawEntry> rawGet(SourceInstanceId source, String externalId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<CollectionRun> recentRuns(int limit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<WorkClaim> claims() {
    throw new UnsupportedOperationException();
  }
}

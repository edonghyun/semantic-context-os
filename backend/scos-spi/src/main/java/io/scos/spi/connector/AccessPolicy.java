package io.scos.spi.connector;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** 원천의 접근 지원·carrier·재관측·정상 문서 묶음과 수집을 하나로 선언한다. 불변이며 여러 run이 공유한다. */
public final class AccessPolicy {
  @FunctionalInterface
  public interface Collector {
    /**
     * 대상이 비어도 그룹·신원 전체를 수집한다. 각 대상은 Observed를 내고 대상별 실패는 FAILED로 계속한다. 전체 디렉터리를 끝까지 낸 경우에만 정상 반환한다.
     * sink 오류·취소·원천 전체 실패는 전파한다.
     */
    void collect(AccessInput input, AclSink sink);
  }

  @FunctionalInterface
  public interface LocalAcl {
    Optional<RawAcl> resolve(SourceDescriptor source, String externalId, Instant observedAt);
  }

  private static final Collector UNIMPLEMENTED =
      (input, sink) -> {
        throw new UnsupportedOperationException("access collection is not implemented");
      };

  private final AccessModel model;
  private final Optional<String> scopeKind;
  private final Set<String> carrierKinds;
  private final Set<String> refreshKinds;
  private final Function<RawItem, Optional<String>> document;
  private final Collector collector;
  private final LocalAcl localAcl;

  private AccessPolicy(
      AccessModel model,
      Optional<String> scopeKind,
      Set<String> carrierKinds,
      Set<String> refreshKinds,
      Function<RawItem, Optional<String>> document,
      Collector collector,
      LocalAcl localAcl) {
    this.model = model;
    this.scopeKind = scopeKind;
    this.carrierKinds = Set.copyOf(carrierKinds);
    this.refreshKinds = Set.copyOf(refreshKinds);
    if (!this.carrierKinds.containsAll(this.refreshKinds))
      throw new IllegalArgumentException("Refresh kinds must carry document access");
    this.document = document;
    this.collector = collector;
    this.localAcl = localAcl;
  }

  /** 원본 하나가 자기 외부 id의 문서 하나를 제한한다. */
  public static AccessPolicy supported(Set<String> carrierKinds, Set<String> refreshKinds) {
    return supported(carrierKinds, refreshKinds, item -> Optional.of(item.externalId()));
  }

  /** 여러 원본이 같은 문서를 제한하면 모두 같은 정규 문서 id를 선언한다. 분류할 수 없는 원본은 empty다. */
  public static AccessPolicy supported(
      Set<String> carrierKinds,
      Set<String> refreshKinds,
      Function<RawItem, Optional<String>> document) {
    return new AccessPolicy(
        AccessModel.SUPPORTED,
        Optional.empty(),
        carrierKinds,
        refreshKinds,
        document,
        UNIMPLEMENTED,
        (source, id, at) -> Optional.empty());
  }

  /** 권한을 관측할 수 없는 원천은 기본적으로 누구에게도 공개하지 않는다. */
  public static AccessPolicy unsupported() {
    return new AccessPolicy(
        AccessModel.UNSUPPORTED,
        Optional.empty(),
        Set.of(),
        Set.of(),
        item -> Optional.empty(),
        UNIMPLEMENTED,
        (source, id, at) -> Optional.empty());
  }

  /** 미지원 원천을 명시적인 인스턴스 지정으로만 공개한다. */
  public static AccessPolicy scoped(String sourceKind) {
    return new AccessPolicy(
        AccessModel.UNSUPPORTED,
        Optional.of(sourceKind),
        Set.of(),
        Set.of(),
        item -> Optional.empty(),
        UNIMPLEMENTED,
        (source, id, at) -> Optional.empty());
  }

  public AccessPolicy collecting(Collector collection) {
    return collecting(collection, localAcl);
  }

  public AccessPolicy collecting(Collector collection, LocalAcl local) {
    return new AccessPolicy(
        model, scopeKind, carrierKinds, refreshKinds, document, collection, local);
  }

  public AccessModel model() {
    return model;
  }

  public boolean permitsScope(SourceDescriptor source) {
    return scopeKind.filter(source.kind()::equals).isPresent();
  }

  public Set<String> carrierKinds() {
    return carrierKinds;
  }

  public Set<String> refreshKinds() {
    return refreshKinds;
  }

  public boolean carries(RawItem item) {
    return carrierKinds.contains(item.resourceType());
  }

  public boolean refreshes(RawItem item) {
    return refreshKinds.contains(item.resourceType());
  }

  public Optional<String> documentOf(RawItem item) {
    return carries(item) ? document.apply(item) : Optional.empty();
  }

  public void collect(AccessInput input, AclSink sink) {
    collector.collect(input, sink);
  }

  public Optional<RawAcl> localAcl(SourceDescriptor source, String id, Instant observedAt) {
    return localAcl.resolve(source, id, observedAt);
  }

  /**
   * 입력 원본의 정규 문서별 carrier 집합과 실제 묶음을 대조한다. 누락·분할·다른 묶음에 중복·변조는 해당 문서 id를 반환한다. 비운반 조각은 비교하지 않는다. 반환
   * id가 실제 묶음에서 사라졌어도 호출자는 FAILED로 닫아야 한다.
   */
  public Set<String> invalidDocuments(List<RawItem> items, Map<String, List<RawItem>> groups) {
    Map<String, Set<RawItem>> expected = expectedCarriers(items);
    Set<String> invalid = new HashSet<>(expected.keySet());
    groups.forEach(
        (id, bundle) -> {
          Set<RawItem> actual = new HashSet<>(bundle.stream().filter(this::carries).toList());
          if (actual.equals(expected.getOrDefault(id, Set.of()))) invalid.remove(id);
          else invalid.add(id);
        });
    return Set.copyOf(invalid);
  }

  private Map<String, Set<RawItem>> expectedCarriers(List<RawItem> items) {
    Map<String, Set<RawItem>> expected = new HashMap<>();
    for (RawItem item : items)
      documentOf(item)
          .ifPresent(id -> expected.computeIfAbsent(id, ignored -> new HashSet<>()).add(item));
    return expected;
  }
}

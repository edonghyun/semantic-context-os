package io.scos.collection.internal;

import io.scos.spi.connector.SourceAdapter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * kind → 수집 어댑터 레지스트리(ADR-0022 §5, TS SourceAdapterResolver). 커넥터 추가 = {@link SourceAdapter} 빈 하나.
 * 같은 kind를 두 어댑터가 맡으면 조립 시점에 거부한다.
 */
public final class SourceAdapters {
  private final Map<String, SourceAdapter> byKind;

  private SourceAdapters(Map<String, SourceAdapter> byKind) {
    this.byKind = byKind;
  }

  public static SourceAdapters of(List<SourceAdapter> adapters) {
    Map<String, SourceAdapter> byKind = new TreeMap<>();
    for (SourceAdapter adapter : adapters) {
      if (byKind.putIfAbsent(adapter.kind(), adapter) != null)
        throw new IllegalArgumentException("source adapter registered twice: " + adapter.kind());
    }
    return new SourceAdapters(byKind);
  }

  /** 등록된 kind(이름 순). */
  public Set<String> kinds() {
    return Collections.unmodifiableSet(byKind.keySet());
  }

  public Optional<SourceAdapter> forKind(String kind) {
    return Optional.ofNullable(byKind.get(kind));
  }

  /** 등록 입력(`source add --kind`)용: 없는 kind는 등록된 kind 목록(이름 순)을 담아 거부한다. */
  public SourceAdapter require(String kind) {
    return forKind(kind)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Source adapter not found: "
                        + kind
                        + ". Registered kinds: "
                        + String.join(", ", byKind.keySet())));
  }
}

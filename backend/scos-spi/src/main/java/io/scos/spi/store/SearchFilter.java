package io.scos.spi.store;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 검색 필터(CONTRACTS B3·B4): archived 포함 여부, partition 제한(null이면 전체). 접근 범위를 명시하며 필터는 top-k 이전에 적용된다.
 */
public record SearchFilter(
    boolean includeArchived, @Nullable List<String> partitions, AccessScope access) {

  public SearchFilter {
    Objects.requireNonNull(access, "access");
    partitions = partitions == null ? null : List.copyOf(partitions);
  }

  public boolean accepts(String partition, boolean archived) {
    if (!includeArchived && archived) return false;
    return partitions == null || partitions.contains(partition);
  }
}

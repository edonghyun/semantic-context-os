package io.scos.spi.connector;

import io.scos.spi.access.DomainPolicy;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 접근 수집 대상. storedContent는 이 소스의 active 원본 JSON을 지연 조회한다. 대상이 비어도 그룹·신원 전체 집합을 수집한다. 본문 수집과 같은 점유에서
 * 사용한다.
 */
public record AccessInput(
    SourceDescriptor source,
    List<String> externalIds,
    CallExecutor calls,
    CancellationToken cancel,
    DomainPolicy domains,
    Function<String, Optional<String>> storedContent) {
  /** 저장 콘텐츠가 없는 직접 호출. 조회할 수 없는 소속은 커넥터가 원격으로 확인하거나 실패로 닫는다. */
  public AccessInput(
      SourceDescriptor source,
      List<String> externalIds,
      CallExecutor calls,
      CancellationToken cancel,
      DomainPolicy domains) {
    this(source, externalIds, calls, cancel, domains, id -> Optional.empty());
  }

  public AccessInput {
    externalIds = List.copyOf(externalIds);
  }
}

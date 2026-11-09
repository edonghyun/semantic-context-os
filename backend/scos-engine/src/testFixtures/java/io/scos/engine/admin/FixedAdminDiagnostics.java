package io.scos.engine.admin;

import java.util.List;
import java.util.Optional;

/** 진단이 없는 저장·변경 계약을 위한 명시적 읽기 응답. */
public final class FixedAdminDiagnostics implements AdminDiagnostics {
  private final List<SourceAccessView> sources;

  public FixedAdminDiagnostics() {
    this(List.of());
  }

  public FixedAdminDiagnostics(List<SourceAccessView> sources) {
    this.sources = List.copyOf(sources);
  }

  @Override
  public List<SourceAccessView> sourceAccess() {
    return sources;
  }

  @Override
  public Optional<DocumentAccessView> explain(String documentId, Optional<String> userId) {
    return Optional.empty();
  }
}

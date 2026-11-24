package io.scos.boardarchive.fetch;

import java.time.LocalDate;
import java.util.Objects;

public record Permission(String grantedBy, LocalDate grantedOn, String scope, String evidence) {
  public Permission {
    if (grantedBy.isBlank() || scope.isBlank() || evidence.isBlank())
      throw new IllegalArgumentException("Permission requires authority, scope and evidence");
    Objects.requireNonNull(grantedOn);
  }
}

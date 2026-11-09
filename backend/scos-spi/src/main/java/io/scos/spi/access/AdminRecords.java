package io.scos.spi.access;

import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 관리 저장 경계의 값. 해시를 포함하는 StoredToken은 세션 내부 조회에만 쓴다. */
public final class AdminRecords {
  private static final int DEFAULT_PAGE_LIMIT = 50;
  private static final int MAX_PAGE_LIMIT = 200;

  private AdminRecords() {}

  public enum Role {
    ADMIN,
    MEMBER
  }

  public enum LinkMethod {
    AUTO,
    ADMIN
  }

  public record UserAccount(
      String id, String email, boolean active, Role role, Instant createdAt) {}

  public record IdentityLink(
      SourceInstanceId source,
      String sourceUserId,
      String userId,
      LinkMethod method,
      Instant linkedAt,
      String linkedBy,
      Set<String> warnings) {
    public IdentityLink {
      warnings = Set.copyOf(warnings);
    }
  }

  public record ScopeDesignation(
      SourceInstanceId source, String designatedBy, Instant designatedAt) {
    /** 원천의 하위 페이지 제한보다 넓은 공개 범위를 관리 응답에 알린다. */
    public String visibilityWarning() {
      return "이 소스의 모든 페이지가 전원에게 보입니다. 원천에서 제한한 하위 페이지도 포함됩니다.";
    }
  }

  public record SourceAccount(SourceInstanceId id, String kind) {}

  public record ObservedIdentity(SourceInstanceId source, SourceIdentityRecord identity) {}

  /** 세션에서만 사용하는 저장 식별자. 공개 응답은 TokenMetadata를 사용한다. */
  public record StoredToken(UUID tokenId, TokenHash hash, String userId) {}

  public record TokenMetadata(
      UUID tokenId,
      String userId,
      String label,
      Instant createdAt,
      Optional<Instant> lastUsedAt,
      Optional<Instant> revokedAt) {}

  /** 감사 detail에는 비밀 대신 짧은 비밀 아닌 사실만 전달한다. */
  public record NewAuditEvent(
      Instant at,
      String actor,
      String action,
      String targetType,
      String targetId,
      Map<String, String> detail) {
    public NewAuditEvent {
      detail = Map.copyOf(detail);
    }
  }

  public record AuditEvent(
      long id,
      Instant at,
      String actor,
      String action,
      String targetType,
      String targetId,
      Map<String, String> detail) {
    public AuditEvent {
      detail = Map.copyOf(detail);
    }
  }

  /** 커서는 저장소가 해석하는 불투명 값이며 잘못된 커서는 IllegalArgumentException이다. */
  public record PageRequest(int limit, Optional<String> after) {
    public PageRequest {
      validateLimit(limit);
    }

    public PageRequest() {
      this(DEFAULT_PAGE_LIMIT, Optional.empty());
    }
  }

  public record Page<T>(List<T> items, Optional<String> nextCursor) {
    public Page {
      items = List.copyOf(items);
    }
  }

  /** 감사는 (at,id) 내림차순이며 각 필터는 있으면 모두 함께 적용한다. */
  public record AuditQuery(
      int limit,
      Optional<String> after,
      Optional<String> action,
      Optional<String> actor,
      Optional<String> targetType,
      Optional<String> targetId) {
    public AuditQuery {
      validateLimit(limit);
    }

    public AuditQuery() {
      this(
          DEFAULT_PAGE_LIMIT,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }
  }

  private static void validateLimit(int limit) {
    if (limit < 1 || limit > MAX_PAGE_LIMIT) {
      throw new IllegalArgumentException("Page limit must be between 1 and 200");
    }
  }
}

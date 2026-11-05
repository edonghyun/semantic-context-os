package io.scos.spi.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 원천 그룹의 구성원 전체 집합 관측. 식별자는 원천 내부 값이며 빈 집합은 구성원이 없음을 뜻한다. observedAt은 응답을 받은 로컬 시각이다. */
public record GroupSnapshot(String groupId, Set<String> members, Instant observedAt, Kind kind) {
  public enum Kind {
    REAL,
    SYNTHETIC
  }

  public GroupSnapshot(String groupId, Set<String> members, Instant observedAt) {
    this(groupId, members, observedAt, Kind.REAL);
  }

  public GroupSnapshot {
    Objects.requireNonNull(groupId, "groupId");
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(kind, "kind");
    if (groupId.startsWith("~") != (kind == Kind.SYNTHETIC))
      throw new IllegalArgumentException("Synthetic group prefix is reserved");
    members = Set.copyOf(members);
  }

  public static GroupSnapshot synthetic(String groupId, Set<String> members, Instant observedAt) {
    return new GroupSnapshot(groupId, members, observedAt, Kind.SYNTHETIC);
  }

  public GroupSnapshot withObservation(Set<String> members, Instant observedAt) {
    return new GroupSnapshot(groupId, members, observedAt, kind);
  }

  /** 정렬된 길이 접두 표현은 순서·구분자 충돌에 영향받지 않는다. 시각은 별도 고수위다. */
  public String fingerprint() {
    String canonical =
        members.stream()
            .sorted()
            .map(value -> value.length() + ":" + value)
            .collect(Collectors.joining());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("JDK must provide SHA-256", error);
    }
  }
}

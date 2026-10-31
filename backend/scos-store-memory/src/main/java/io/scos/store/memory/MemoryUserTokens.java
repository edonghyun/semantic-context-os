package io.scos.store.memory;

import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.store.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 신원 저장소의 현재 활성 상태와 토큰별 폐기·사용 시각을 함께 확인한다. */
public final class MemoryUserTokens implements UserTokens {
  private static final DomainPolicy NO_DOMAINS = new DomainPolicy(Set.of(), Map.of());
  private final Map<TokenHash, Stored> values = new ConcurrentHashMap<>();
  private final MemoryIdentityStore users;

  public MemoryUserTokens(MemoryIdentityStore users) {
    this.users = users;
  }

  @Override
  public Optional<Principal> authenticate(TokenHash hash) {
    return Optional.ofNullable(values.get(hash))
        .filter(t -> t.revokedAt().isEmpty())
        .filter(t -> users.accessKeysOf(t.userId(), NO_DOMAINS).isPresent())
        .map(t -> new Principal(t.userId()));
  }

  @Override
  public void store(TokenHash hash, String userId, String label, Instant at) {
    if (!users.containsUser(userId)) throw new IllegalArgumentException("Unknown user: " + userId);
    if (values.putIfAbsent(hash, new Stored(userId, label, at, Optional.empty(), Optional.empty()))
        != null) throw new IllegalStateException("Token hash already stored");
  }

  @Override
  public void revoke(TokenHash hash, Instant at) {
    values.computeIfPresent(
        hash,
        (key, token) ->
            token.revokedAt().isPresent()
                ? token
                : new Stored(
                    token.userId(),
                    token.label(),
                    token.createdAt(),
                    Optional.of(at),
                    token.lastUsedAt()));
  }

  @Override
  public void revokeAllOf(String userId, Instant at) {
    values.replaceAll(
        (hash, token) ->
            token.userId().equals(userId) && token.revokedAt().isEmpty()
                ? new Stored(
                    token.userId(),
                    token.label(),
                    token.createdAt(),
                    Optional.of(at),
                    token.lastUsedAt())
                : token);
  }

  @Override
  public void markUsed(Map<TokenHash, Instant> lastUsed) {
    lastUsed.forEach(
        (hash, at) ->
            values.computeIfPresent(
                hash,
                (key, token) ->
                    token.lastUsedAt().filter(previous -> !at.isAfter(previous)).isPresent()
                        ? token
                        : new Stored(
                            token.userId(),
                            token.label(),
                            token.createdAt(),
                            token.revokedAt(),
                            Optional.of(at))));
  }

  Optional<Instant> revokedAt(TokenHash hash) {
    return Optional.ofNullable(values.get(hash)).flatMap(Stored::revokedAt);
  }

  Optional<Instant> lastUsedAt(TokenHash hash) {
    return Optional.ofNullable(values.get(hash)).flatMap(Stored::lastUsedAt);
  }

  private record Stored(
      String userId,
      String label,
      Instant createdAt,
      Optional<Instant> revokedAt,
      Optional<Instant> lastUsedAt) {}
}

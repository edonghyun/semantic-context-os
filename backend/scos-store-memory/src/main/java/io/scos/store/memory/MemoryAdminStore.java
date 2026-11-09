package io.scos.store.memory;

import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.ObservedIdentity;
import io.scos.spi.access.AdminRecords.Page;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.ScopeDesignation;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.StoredToken;
import io.scos.spi.access.AdminRecords.TokenMetadata;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.TokenHash;
import io.scos.spi.access.UserTokens;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import io.scos.store.memory.MemoryIdentityStore.Directory;
import io.scos.store.memory.MemoryIdentityStore.Link;
import io.scos.store.memory.MemoryIdentityStore.User;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 신원·인증·수집과 동일한 잠금 아래 관리 변경과 감사를 커밋한다. */
public final class MemoryAdminStore implements AdminStore {
  private final MemoryIdentityStore identities;
  private final MemoryUserTokens tokens;
  private final MemorySourceAccessDirectory directory;
  private final List<AuditEvent> events = new ArrayList<>();
  private long nextAuditId;

  public MemoryAdminStore(
      MemoryIdentityStore identities,
      MemoryUserTokens tokens,
      MemorySourceAccessDirectory directory) {
    if (!tokens.belongsTo(identities) || !directory.belongsTo(identities))
      throw new IllegalArgumentException("Administration must share identity state");
    this.identities = identities;
    this.tokens = tokens;
    this.directory = directory;
  }

  @Override
  public <T> T transact(Function<Session, T> work) {
    return identities.withDirectoryLock(() -> execute(work));
  }

  private <T> T execute(Function<Session, T> work) {
    var before = identities.snapshot();
    var beforeTokens = tokens.snapshot();
    var beforeDirectory = directory.snapshot();
    var beforeEvents = List.copyOf(events);
    long beforeId = nextAuditId;
    var session = new Transaction();
    try {
      return work.apply(session);
    } catch (RuntimeException | Error failure) {
      identities.replace(before);
      tokens.restore(beforeTokens);
      directory.restore(beforeDirectory);
      events.clear();
      events.addAll(beforeEvents);
      nextAuditId = beforeId;
      throw failure;
    } finally {
      session.open = false;
    }
  }

  private final class Transaction implements Session, UserTokens {
    private final Thread owner = Thread.currentThread();
    private boolean open = true;

    private void requireOpen() {
      if (!open || !Thread.currentThread().equals(owner))
        throw new IllegalStateException(
            "Administration session is closed or belongs to another thread");
    }

    @Override
    public void lockAdministration() {
      requireOpen();
    }

    @Override
    public int administratorCount() {
      requireOpen();
      return (int)
          identities.snapshot().users().stream().filter(u -> u.role() == Role.ADMIN).count();
    }

    @Override
    public int activeAdministratorCount() {
      requireOpen();
      return (int)
          identities.snapshot().users().stream()
              .filter(u -> u.role() == Role.ADMIN && u.active())
              .count();
    }

    @Override
    public int tokenHoldingAdministratorCount() {
      requireOpen();
      var holders =
          tokens.snapshot().values().stream()
              .filter(token -> token.revokedAt().isEmpty())
              .map(MemoryUserTokens.Stored::userId)
              .collect(Collectors.toSet());
      return (int)
          identities.snapshot().users().stream()
              .filter(user -> user.active() && user.role() == Role.ADMIN)
              .filter(user -> holders.contains(user.id()))
              .count();
    }

    @Override
    public Optional<UserAccount> user(String id) {
      requireOpen();
      return identities.snapshot().users().stream()
          .filter(u -> u.id().equals(id))
          .findFirst()
          .map(this::account);
    }

    @Override
    public Optional<UserAccount> userByEmail(String email) {
      requireOpen();
      return identities.snapshot().users().stream()
          .filter(u -> u.email().equals(email))
          .findFirst()
          .map(this::account);
    }

    @Override
    public Page<UserAccount> users(PageRequest request) {
      requireOpen();
      return page(
          identities.snapshot().users().stream().map(this::account).toList(),
          request,
          "users",
          UserAccount::id);
    }

    @Override
    public void insertUser(UserAccount user) {
      if (user(user.id()).isPresent() || userByEmail(user.email()).isPresent())
        throw new IllegalStateException("User id or email already exists");
      if (!user.email().equals(user.email().toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("Email must be lowercase");
      var current = identities.snapshot();
      var users = new ArrayList<>(current.users());
      users.add(new User(user.id(), user.email(), user.active(), user.role(), user.createdAt()));
      replaceUsers(users);
    }

    @Override
    public boolean setActive(String id, boolean active) {
      var old = user(id);
      if (old.isEmpty() || old.get().active() == active) return false;
      replaceUsers(
          identities.snapshot().users().stream()
              .map(
                  u ->
                      u.id().equals(id)
                          ? new User(u.id(), u.email(), active, u.role(), u.createdAt())
                          : u)
              .toList());
      return true;
    }

    @Override
    public boolean setRole(String id, Role role) {
      var old = user(id);
      if (old.isEmpty() || old.get().role() == role) return false;
      replaceUsers(
          identities.snapshot().users().stream()
              .map(
                  u ->
                      u.id().equals(id)
                          ? new User(u.id(), u.email(), u.active(), role, u.createdAt())
                          : u)
              .toList());
      return true;
    }

    private void replaceUsers(List<User> users) {
      var current = identities.snapshot();
      identities.replace(
          new Directory(
              users,
              current.identities(),
              current.links(),
              current.groups(),
              current.scopes(),
              current.sources()));
    }

    private UserAccount account(User user) {
      return new UserAccount(user.id(), user.email(), user.active(), user.role(), user.createdAt());
    }

    @Override
    public boolean lockSource(SourceInstanceId source) {
      return source(source).isPresent();
    }

    @Override
    public Optional<SourceAccount> source(SourceInstanceId source) {
      requireOpen();
      return identities.snapshot().sources().stream()
          .filter(s -> s.id().equals(source))
          .findFirst();
    }

    @Override
    public List<SourceAccount> sources() {
      requireOpen();
      return identities.snapshot().sources().stream()
          .sorted(Comparator.comparing(s -> s.id().value()))
          .toList();
    }

    @Override
    public List<ObservedIdentity> observedIdentities(SourceInstanceId source) {
      requireOpen();
      return directory.current(source).identities().values().stream()
          .sorted(Comparator.comparing(i -> i.sourceUserId()))
          .map(i -> new ObservedIdentity(source, i))
          .toList();
    }

    private IdentityLink identityLink(Link link) {
      return new IdentityLink(
          link.instance(),
          link.id(),
          link.userId(),
          link.method(),
          link.linkedAt(),
          link.linkedBy(),
          Set.of());
    }

    @Override
    public Optional<IdentityLink> link(SourceInstanceId source, String id) {
      requireOpen();
      return identities.snapshot().links().stream()
          .filter(l -> l.instance().equals(source) && l.id().equals(id))
          .findFirst()
          .map(this::identityLink);
    }

    @Override
    public boolean putLink(IdentityLink link) {
      if (user(link.userId()).isEmpty()) throw new IllegalArgumentException("Unknown user");
      var old = link(link.source(), link.sourceUserId());
      if (old.isPresent() && !old.get().userId().equals(link.userId()))
        throw new IllegalStateException("Identity already linked to another user");
      var value =
          new Link(
              link.source(),
              link.sourceUserId(),
              link.userId(),
              link.method(),
              link.linkedAt(),
              link.linkedBy());
      var current = identities.snapshot();
      if (current.links().contains(value)) return false;
      var links = new ArrayList<>(current.links());
      links.removeIf(l -> l.instance().equals(link.source()) && l.id().equals(link.sourceUserId()));
      links.add(value);
      replaceLinks(links);
      return true;
    }

    @Override
    public boolean removeLink(SourceInstanceId source, String id) {
      requireOpen();
      var links = new ArrayList<>(identities.snapshot().links());
      if (!links.removeIf(l -> l.instance().equals(source) && l.id().equals(id))) return false;
      replaceLinks(links);
      return true;
    }

    private void replaceLinks(List<Link> links) {
      var current = identities.snapshot();
      identities.replace(
          new Directory(
              current.users(),
              current.identities(),
              links,
              current.groups(),
              current.scopes(),
              current.sources()));
    }

    @Override
    public Page<IdentityLink> links(Optional<String> userId, PageRequest request) {
      requireOpen();
      return page(
          identities.snapshot().links().stream()
              .filter(l -> userId.isEmpty() || userId.get().equals(l.userId()))
              .map(this::identityLink)
              .toList(),
          request,
          "links",
          l -> l.source().value() + "\0" + l.sourceUserId());
    }

    @Override
    public boolean designate(ScopeDesignation scope) {
      if (scopes().stream().anyMatch(s -> s.source().equals(scope.source()))) return false;
      var scopes = new ArrayList<>(scopes());
      scopes.add(scope);
      replaceScopes(scopes);
      return true;
    }

    @Override
    public boolean revokeScope(SourceInstanceId source) {
      var scopes = new ArrayList<>(scopes());
      if (!scopes.removeIf(s -> s.source().equals(source))) return false;
      replaceScopes(scopes);
      return true;
    }

    private void replaceScopes(List<ScopeDesignation> scopes) {
      var current = identities.snapshot();
      identities.replace(
          new Directory(
              current.users(),
              current.identities(),
              current.links(),
              current.groups(),
              scopes,
              current.sources()));
    }

    @Override
    public List<ScopeDesignation> scopes() {
      requireOpen();
      return identities.snapshot().scopes().stream()
          .sorted(Comparator.comparing(s -> s.source().value()))
          .toList();
    }

    @Override
    public UserTokens tokens() {
      requireOpen();
      return this;
    }

    @Override
    public Optional<StoredToken> token(UUID id) {
      requireOpen();
      return tokens.snapshot().entrySet().stream()
          .filter(e -> e.getValue().tokenId().equals(id))
          .findFirst()
          .map(e -> new StoredToken(e.getValue().tokenId(), e.getKey(), e.getValue().userId()));
    }

    @Override
    public Optional<StoredToken> tokenByHash(TokenHash hash) {
      requireOpen();
      return Optional.ofNullable(tokens.snapshot().get(hash))
          .map(t -> new StoredToken(t.tokenId(), hash, t.userId()));
    }

    @Override
    public Page<TokenMetadata> tokenPage(String user, PageRequest request) {
      requireOpen();
      return page(
          tokens.snapshot().values().stream()
              .filter(t -> t.userId().equals(user))
              .map(
                  t ->
                      new TokenMetadata(
                          t.tokenId(),
                          t.userId(),
                          t.label(),
                          t.createdAt(),
                          t.lastUsedAt(),
                          t.revokedAt()))
              .toList(),
          request,
          "tokens",
          t -> t.tokenId().toString());
    }

    @Override
    public void appendAudit(NewAuditEvent event) {
      requireOpen();
      Objects.requireNonNull(event.actor(), "Audit actor is required");
      events.add(
          new AuditEvent(
              ++nextAuditId,
              event.at(),
              event.actor(),
              event.action(),
              event.targetType(),
              event.targetId(),
              event.detail()));
    }

    @Override
    public Page<AuditEvent> audit(AuditQuery query) {
      requireOpen();
      var after = query.after().map(value -> decode("audit", value));
      var sorted =
          events.stream()
              .filter(e -> matches(query, e))
              .sorted(
                  Comparator.comparing(AuditEvent::at).thenComparingLong(AuditEvent::id).reversed())
              .filter(e -> after.isEmpty() || olderThan(e, after.get()))
              .limit(query.limit() + 1L)
              .toList();
      var items = sorted.stream().limit(query.limit()).toList();
      return new Page<>(
          items,
          sorted.size() > query.limit()
              ? Optional.of(encode("audit", auditKey(items.getLast())))
              : Optional.empty());
    }

    private boolean matches(AuditQuery q, AuditEvent e) {
      return q.action().map(e.action()::equals).orElse(true)
          && q.actor().map(e.actor()::equals).orElse(true)
          && q.targetType().map(e.targetType()::equals).orElse(true)
          && q.targetId().map(e.targetId()::equals).orElse(true);
    }

    private boolean olderThan(AuditEvent event, String key) {
      try {
        var parts = key.split("\\|", -1);
        var at = Instant.parse(parts[0]);
        var id = Long.parseLong(parts[1]);
        return event.at().isBefore(at) || (event.at().equals(at) && event.id() < id);
      } catch (RuntimeException invalid) {
        throw new IllegalArgumentException("Invalid audit cursor", invalid);
      }
    }

    private String auditKey(AuditEvent event) {
      return event.at() + "|" + event.id();
    }

    @Override
    public Optional<Principal> authenticate(TokenHash hash) {
      requireOpen();
      return tokens.authenticate(hash);
    }

    @Override
    public void store(TokenHash hash, String user, String label, Instant at) {
      requireOpen();
      tokens.store(hash, user, label, at);
    }

    @Override
    public void revoke(TokenHash hash, Instant at) {
      requireOpen();
      tokens.revoke(hash, at);
    }

    @Override
    public void revokeAllOf(String user, Instant at) {
      requireOpen();
      tokens.revokeAllOf(user, at);
    }

    @Override
    public void markUsed(Map<TokenHash, Instant> used) {
      requireOpen();
      tokens.markUsed(used);
    }
  }

  private <T> Page<T> page(
      List<T> values, PageRequest request, String kind, Function<T, String> key) {
    var after = request.after().map(value -> decode(kind, value));
    var sorted =
        values.stream()
            .sorted(Comparator.comparing(key))
            .filter(value -> after.isEmpty() || key.apply(value).compareTo(after.get()) > 0)
            .limit(request.limit() + 1L)
            .toList();
    var items = sorted.stream().limit(request.limit()).toList();
    return new Page<>(
        items,
        sorted.size() > request.limit()
            ? Optional.of(encode(kind, key.apply(items.getLast())))
            : Optional.empty());
  }

  private String encode(String kind, String key) {
    return kind
        + "."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(key.getBytes(StandardCharsets.UTF_8));
  }

  private String decode(String kind, String cursor) {
    if (!cursor.startsWith(kind + ".")) throw new IllegalArgumentException("Invalid cursor");
    try {
      var key =
          new String(
              Base64.getUrlDecoder().decode(cursor.substring(kind.length() + 1)),
              StandardCharsets.UTF_8);
      if (key.isEmpty() || !encode(kind, key).equals(cursor))
        throw new IllegalArgumentException("Invalid cursor");
      validateCursor(kind, key);
      return key;
    } catch (DateTimeException | IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid cursor", invalid);
    }
  }

  private void validateCursor(String kind, String key) {
    switch (kind) {
      case "tokens" -> UUID.fromString(key);
      case "links" -> {
        var parts = key.split("\0", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty())
          throw new IllegalArgumentException("Invalid link cursor");
      }
      case "audit" -> {
        var parts = key.split("\\|", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid audit cursor");
        Instant.parse(parts[0]);
        if (Long.parseLong(parts[1]) < 1)
          throw new IllegalArgumentException("Invalid audit cursor");
      }
      default -> {}
    }
  }
}

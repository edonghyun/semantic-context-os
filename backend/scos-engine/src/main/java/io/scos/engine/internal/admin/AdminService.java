package io.scos.engine.internal.admin;

import io.scos.engine.admin.AdminDiagnostics;
import io.scos.engine.admin.AdminDiagnostics.DocumentAccessView;
import io.scos.engine.admin.AdminDiagnostics.SourceAccessView;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.IssuedToken;
import io.scos.spi.access.AdminRecords.AuditEvent;
import io.scos.spi.access.AdminRecords.AuditQuery;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.LinkMethod;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.ObservedIdentity;
import io.scos.spi.access.AdminRecords.Page;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.ScopeDesignation;
import io.scos.spi.access.AdminRecords.StoredToken;
import io.scos.spi.access.AdminRecords.TokenMetadata;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.AdminStore.Session;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 현재 권한 확인·마지막 관리자 보호·사용자 변경·감사를 같은 관리 잠금 안에서 수행한다. */
public final class AdminService implements AdminPort {
  private final AdminStore store;
  private final Clock clock;
  private final AdminDiagnostics diagnostics;
  private final TokenIssuer issuer = new TokenIssuer(new SecureRandom());

  public AdminService(AdminStore store, Clock clock, AdminDiagnostics diagnostics) {
    this.store = store;
    this.clock = clock;
    this.diagnostics = diagnostics;
  }

  @Override
  public UserAccount createUser(Principal actor, String email, Role role) {
    return mutate(
        actor,
        session -> {
          String normalized =
              AccountEmails.normalize(email).orElseThrow(() -> new Failure("invalid_request"));
          if (session.userByEmail(normalized).isPresent()) throw new Failure("conflict");
          var user =
              new UserAccount(
                  UUID.randomUUID().toString(), normalized, true, role, clock.instant());
          session.insertUser(user);
          audit(session, actor, "user.create", user.id(), Map.of("role", wire(role)));
          return user;
        });
  }

  @Override
  public void deactivateUser(Principal actor, String userId) {
    mutate(
        actor,
        session -> {
          var target = requireUser(session, userId);
          if (!target.active()) return Optional.empty();
          session.setActive(userId, false);
          session.tokens().revokeAllOf(userId, clock.instant());
          protectLastAdministrator(session, target);
          audit(session, actor, "user.deactivate", userId, Map.of());
          return Optional.empty();
        });
  }

  @Override
  public void activateUser(Principal actor, String userId) {
    mutate(
        actor,
        session -> {
          requireUser(session, userId);
          if (!session.setActive(userId, true)) return Optional.empty();
          audit(session, actor, "user.activate", userId, Map.of());
          return Optional.empty();
        });
  }

  @Override
  public void changeRole(Principal actor, String userId, Role role) {
    mutate(
        actor,
        session -> {
          var target = requireUser(session, userId);
          if (target.role() == role) return Optional.empty();
          session.setRole(userId, role);
          if (role == Role.MEMBER) protectLastAdministrator(session, target);
          audit(
              session,
              actor,
              "user.role",
              userId,
              Map.of("old", wire(target.role()), "new", wire(role)));
          return Optional.empty();
        });
  }

  @Override
  public Page<UserAccount> users(Principal actor, PageRequest page) {
    return transact(
        session -> {
          requireAdministrator(session, actor);
          try {
            return session.users(page);
          } catch (IllegalArgumentException invalid) {
            throw new Failure("invalid_request");
          }
        });
  }

  @Override
  public IssuedToken issueToken(Principal actor, String userId, String label) {
    return transact(
        session -> {
          session.lockAdministration();
          requireTokenOwner(session, actor, userId);
          if (!requireUser(session, userId).active()) throw new Failure("conflict");
          String normalized = normalizeLabel(label);
          var secret = issuer.issue();
          session.tokens().store(secret.hash(), userId, normalized, clock.instant());
          var stored = session.tokenByHash(secret.hash()).orElseThrow();
          auditToken(session, actor, "token.issue", stored.tokenId());
          return new IssuedToken(stored.tokenId(), secret.value());
        });
  }

  @Override
  public void revokeToken(Principal actor, UUID tokenId) {
    transact(
        session -> {
          session.lockAdministration();
          requireActiveActor(session, actor);
          var stored = session.token(tokenId).orElseThrow(() -> new Failure("not_found"));
          requireTokenOwner(session, actor, stored.userId());
          if (isRevoked(session, stored)) return Optional.empty();
          session.tokens().revoke(stored.hash(), clock.instant());
          protectLastAdministrator(session, requireUser(session, stored.userId()));
          auditToken(session, actor, "token.revoke", tokenId);
          return Optional.empty();
        });
  }

  @Override
  public Page<TokenMetadata> tokens(Principal actor, String userId, PageRequest page) {
    return transact(
        session -> {
          requireTokenOwner(session, actor, userId);
          requireUser(session, userId);
          try {
            return session.tokenPage(userId, page);
          } catch (IllegalArgumentException invalid) {
            throw new Failure("invalid_request");
          }
        });
  }

  @Override
  public void linkIdentity(
      Principal actor, String userId, SourceInstanceId source, String sourceUserId) {
    mutate(
        actor,
        session -> {
          requireSource(session, source);
          requireUser(session, userId);
          var current = session.link(source, sourceUserId);
          if (current.isPresent()) {
            if (!current.get().userId().equals(userId)) throw new Failure("conflict");
            if (current.get().method() == LinkMethod.ADMIN) return Optional.empty();
          }
          var link =
              new IdentityLink(
                  source,
                  sourceUserId,
                  userId,
                  LinkMethod.ADMIN,
                  clock.instant(),
                  actor.userId(),
                  Set.of());
          session.putLink(link);
          auditIdentity(session, actor, "identity.link", link);
          return Optional.empty();
        });
  }

  @Override
  public void unlinkIdentity(Principal actor, SourceInstanceId source, String sourceUserId) {
    mutate(
        actor,
        session -> {
          requireSource(session, source);
          var current = session.link(source, sourceUserId);
          if (current.isEmpty()) return Optional.empty();
          session.removeLink(source, sourceUserId);
          auditIdentity(session, actor, "identity.unlink", current.get());
          return Optional.empty();
        });
  }

  @Override
  public Page<IdentityLink> links(Principal actor, Optional<String> userId, PageRequest page) {
    return transact(
        session -> {
          requireAdministrator(session, actor);
          try {
            var result = session.links(userId, page);
            var observations = new HashMap<SourceInstanceId, List<ObservedIdentity>>();
            var items =
                result.items().stream()
                    .map(
                        link ->
                            withWarnings(
                                link,
                                observations.computeIfAbsent(
                                    link.source(), session::observedIdentities)))
                    .toList();
            return new Page<>(items, result.nextCursor());
          } catch (IllegalArgumentException invalid) {
            throw new Failure("invalid_request");
          }
        });
  }

  @Override
  public ScopeDesignation designateScope(Principal actor, SourceInstanceId source) {
    return mutate(
        actor,
        session -> {
          requireNotionSource(session, source);
          var current =
              session.scopes().stream().filter(scope -> scope.source().equals(source)).findFirst();
          if (current.isPresent()) return current.get();
          var designation = new ScopeDesignation(source, actor.userId(), clock.instant());
          session.designate(designation);
          auditScope(session, actor, "scope.designate", source);
          return designation;
        });
  }

  @Override
  public void revokeScope(Principal actor, SourceInstanceId source) {
    mutate(
        actor,
        session -> {
          requireNotionSource(session, source);
          if (!session.revokeScope(source)) return Optional.empty();
          auditScope(session, actor, "scope.revoke", source);
          return Optional.empty();
        });
  }

  @Override
  public List<ScopeDesignation> scopes(Principal actor) {
    return transact(
        session -> {
          requireAdministrator(session, actor);
          return session.scopes();
        });
  }

  @Override
  public Page<AuditEvent> audit(Principal actor, AuditQuery query) {
    return transact(
        session -> {
          requireAdministrator(session, actor);
          try {
            return session.audit(query);
          } catch (IllegalArgumentException invalid) {
            throw new Failure("invalid_request");
          }
        });
  }

  @Override
  public List<SourceAccessView> sourceAccess(Principal actor) {
    return transact(
        session -> {
          requireAdministrator(session, actor);
          return diagnostics.sourceAccess();
        });
  }

  @Override
  public Optional<DocumentAccessView> explainAccess(
      Principal actor, String documentId, Optional<String> userId) {
    return transact(
        session -> {
          requireAdministrator(session, actor);
          return diagnostics.explain(documentId, userId);
        });
  }

  private void requireNotionSource(Session session, SourceInstanceId source) {
    requireSource(session, source);
    var account = session.source(source).orElseThrow(() -> new Failure("not_found"));
    if (!account.kind().equals("notion")) throw new Failure("invalid_request");
  }

  private void auditScope(
      Session session, Principal actor, String action, SourceInstanceId source) {
    session.appendAudit(
        new NewAuditEvent(
            clock.instant(), actor.userId(), action, "scope", source.value(), Map.of()));
  }

  private void requireSource(Session session, SourceInstanceId source) {
    if (!session.lockSource(source)) throw new Failure("not_found");
  }

  private IdentityLink withWarnings(IdentityLink link, List<ObservedIdentity> observations) {
    var identity =
        observations.stream()
            .map(ObservedIdentity::identity)
            .filter(observed -> observed.sourceUserId().equals(link.sourceUserId()))
            .findFirst();
    var warnings = new HashSet<String>();
    if (identity.isEmpty()) {
      warnings.add("unobserved");
    } else {
      if (identity.get().guest()) warnings.add("guest");
      if (identity.get().deleted()) warnings.add("deleted");
    }
    return new IdentityLink(
        link.source(),
        link.sourceUserId(),
        link.userId(),
        link.method(),
        link.linkedAt(),
        link.linkedBy(),
        warnings);
  }

  private void auditIdentity(Session session, Principal actor, String action, IdentityLink link) {
    session.appendAudit(
        new NewAuditEvent(
            clock.instant(),
            actor.userId(),
            action,
            "identity",
            link.source().value() + ":" + link.sourceUserId(),
            Map.of(
                "userId", link.userId(), "method", link.method().name().toLowerCase(Locale.ROOT))));
  }

  private UserAccount requireActiveActor(Session session, Principal actor) {
    var current = session.user(actor.userId());
    if (current.isEmpty() || !current.get().active()) throw new Failure("forbidden");
    return current.get();
  }

  private void requireTokenOwner(Session session, Principal actor, String userId) {
    var current = requireActiveActor(session, actor);
    if (current.role() != Role.ADMIN && !current.id().equals(userId))
      throw new Failure("forbidden");
  }

  private boolean isRevoked(Session session, StoredToken stored) {
    var request = new PageRequest();
    while (true) {
      var page = session.tokenPage(stored.userId(), request);
      var target =
          page.items().stream()
              .filter(token -> token.tokenId().equals(stored.tokenId()))
              .findFirst();
      if (target.isPresent()) return target.get().revokedAt().isPresent();
      if (page.nextCursor().isEmpty()) throw new IllegalStateException("Token metadata missing");
      request = new PageRequest(request.limit(), page.nextCursor());
    }
  }

  private String normalizeLabel(String label) {
    String normalized = label.strip();
    if (normalized.isEmpty()
        || normalized.length() > 100
        || label.codePoints().anyMatch(Character::isISOControl))
      throw new Failure("invalid_request");
    return normalized;
  }

  private void auditToken(Session session, Principal actor, String action, UUID tokenId) {
    session.appendAudit(
        new NewAuditEvent(
            clock.instant(), actor.userId(), action, "token", tokenId.toString(), Map.of()));
  }

  private <T> T mutate(Principal actor, Function<Session, T> change) {
    return transact(
        session -> {
          session.lockAdministration();
          requireAdministrator(session, actor);
          return change.apply(session);
        });
  }

  private <T> T transact(Function<Session, T> work) {
    try {
      return store.transact(work);
    } catch (Failure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new IllegalStateException("Administration storage failed", failure);
    }
  }

  private void requireAdministrator(Session session, Principal actor) {
    var current = session.user(actor.userId());
    if (current.isEmpty() || !current.get().active() || current.get().role() != Role.ADMIN)
      throw new Failure("forbidden");
  }

  private UserAccount requireUser(Session session, String userId) {
    return session.user(userId).orElseThrow(() -> new Failure("not_found"));
  }

  private void protectLastAdministrator(Session session, UserAccount target) {
    if (isActiveAdministrator(target) && session.tokenHoldingAdministratorCount() == 0)
      throw new Failure("conflict");
  }

  private boolean isActiveAdministrator(UserAccount user) {
    return user.active() && user.role() == Role.ADMIN;
  }

  private String wire(Role role) {
    return role.name().toLowerCase(Locale.ROOT);
  }

  private void audit(
      Session session, Principal actor, String action, String userId, Map<String, String> detail) {
    session.appendAudit(
        new NewAuditEvent(clock.instant(), actor.userId(), action, "user", userId, detail));
  }
}

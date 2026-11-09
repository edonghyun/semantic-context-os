package io.scos.engine.internal.admin;

import io.scos.engine.admin.AdminBootstrap;
import io.scos.engine.admin.IssuedToken;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.AdminStore.Session;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 관리 잠금 안에서 최초 관리자 생성·감사·보호 파일 전달을 마치고 커밋한다. */
public final class BootstrapAdmin implements AdminBootstrap {
  private final AdminStore store;
  private final Clock clock;
  private final TokenIssuer issuer = new TokenIssuer(new SecureRandom());

  public BootstrapAdmin(AdminStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Override
  public BootstrapResult bootstrap(String email, TokenDelivery delivery) {
    String normalized =
        AccountEmails.normalize(email).orElseThrow(() -> new Failure("invalid_request", false));
    var completed = new AtomicReference<BootstrapResult>();
    try {
      return store.transact(
          session -> {
            session.lockAdministration();
            if (session.administratorCount() != 0) throw new Failure("already_initialized", false);
            var result = createAdministrator(session, normalized, delivery);
            completed.set(result);
            return result;
          });
    } catch (Failure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      var result = completed.get();
      throw new Failure("bootstrap_failed", result != null && requiresRecovery(result), failure);
    }
  }

  private BootstrapResult createAdministrator(
      Session session, String email, TokenDelivery delivery) {
    var at = clock.instant();
    var user = new UserAccount(UUID.randomUUID().toString(), email, true, Role.ADMIN, at);
    session.insertUser(user);
    var secret = issuer.issue();
    session.tokens().store(secret.hash(), user.id(), "bootstrap", at);
    var token = session.tokenByHash(secret.hash()).orElseThrow();
    session.appendAudit(
        new NewAuditEvent(at, "system", "user.create", "user", user.id(), Map.of("role", "admin")));
    session.appendAudit(
        new NewAuditEvent(
            at, "system", "token.issue", "token", token.tokenId().toString(), Map.of()));
    delivery.save(new IssuedToken(token.tokenId(), secret.value()));
    return new BootstrapResult(user.id(), token.tokenId());
  }

  /** 커밋 예외만으로 rollback을 단정하지 않는다. 새 트랜잭션에서 부재를 확인한 경우에만 파일을 지워도 된다. */
  private boolean requiresRecovery(BootstrapResult result) {
    try {
      return store.transact(
          session -> {
            session.lockAdministration();
            return session.token(result.tokenId()).isPresent();
          });
    } catch (RuntimeException unavailable) {
      return true;
    }
  }
}

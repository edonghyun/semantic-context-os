package io.scos.engine.internal.admin;

import io.scos.engine.EventLog;
import io.scos.engine.admin.IdentityReconciler;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.LinkMethod;
import io.scos.spi.access.AdminRecords.NewAuditEvent;
import io.scos.spi.access.AdminRecords.ObservedIdentity;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.AdminStore.Session;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.source.SourceInstanceId;
import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 현재 관측·연결의 결정과 system 감사를 소스별 관리 트랜잭션 안에 가둔다. */
public final class AutomaticIdentityLinks implements IdentityReconciler {
  private static final Logger log = LoggerFactory.getLogger(AutomaticIdentityLinks.class);
  private final AdminStore store;
  private final Clock clock;
  private final IdentityLinkPolicy policy;

  public AutomaticIdentityLinks(AdminStore store, Clock clock, DomainPolicy domains) {
    this.store = store;
    this.clock = clock;
    policy = new IdentityLinkPolicy(domains);
  }

  @Override
  public ReconcileReport reconcile(SourceInstanceId source) {
    try {
      return store.transact(session -> reconcileSource(session, source));
    } catch (RuntimeException failure) {
      EventLog.warn(log, "admin.identity_reconcile.source_failed")
          .source(source)
          .detail(
              Map.of(
                  "errorType",
                  failure.getClass().getSimpleName(),
                  "ref",
                  UUID.randomUUID().toString().replace("-", "").substring(0, 12)))
          .log();
      // 세션 반환값은 커밋 전까지 확정된 성공이 아니다. 롤백된 변화는 집계하지 않는다.
      return new ReconcileReport(0, 0, 0, 1);
    }
  }

  @Override
  public ReconcileReport reconcileAll(CancellationToken cancel) {
    var result = new ReconcileReport(0, 0, 0, 0);
    if (cancel.isCancelled()) return result;
    var sourceIds =
        store.transact(Session::sources).stream()
            .map(SourceAccount::id)
            .sorted(Comparator.comparing(SourceInstanceId::value))
            .toList();
    for (var source : sourceIds) {
      if (cancel.isCancelled() || Thread.currentThread().isInterrupted()) break;
      result = plus(result, reconcile(source));
    }
    return result;
  }

  private ReconcileReport reconcileSource(Session session, SourceInstanceId source) {
    session.lockAdministration();
    if (!session.lockSource(source)) return new ReconcileReport(0, 0, 0, 0);
    var account = session.source(source).orElseThrow();
    var result = new ReconcileReport(0, 0, 0, 0);
    // PARTIAL에서 누락된 신원은 해제 근거가 없다. 전체 roster 누락은 수집이 deleted로 확정한다.
    for (var observed : session.observedIdentities(source))
      result = plus(result, reconcileIdentity(session, account, observed));
    return result;
  }

  private ReconcileReport reconcileIdentity(
      Session session, SourceAccount source, ObservedIdentity observed) {
    String sourceUserId = observed.identity().sourceUserId();
    var current = session.link(source.id(), sourceUserId);
    if (current.filter(link -> link.method() == LinkMethod.ADMIN).isPresent())
      return new ReconcileReport(0, 0, 1, 0);
    var target = policy.matchingUser(source, observed, session);
    if (current.map(IdentityLink::userId).equals(target.map(UserAccount::id)))
      return new ReconcileReport(0, 0, 1, 0);
    current.ifPresent(
        link -> {
          session.removeLink(source.id(), sourceUserId);
          audit(session, "identity.unlink", link);
        });
    target.ifPresent(user -> link(session, source.id(), sourceUserId, user.id()));
    return new ReconcileReport(target.isPresent() ? 1 : 0, current.isPresent() ? 1 : 0, 0, 0);
  }

  private void link(Session session, SourceInstanceId source, String sourceUserId, String userId) {
    var link =
        new IdentityLink(
            source, sourceUserId, userId, LinkMethod.AUTO, clock.instant(), "system", Set.of());
    session.putLink(link);
    audit(session, "identity.link", link);
  }

  private void audit(Session session, String action, IdentityLink link) {
    session.appendAudit(
        new NewAuditEvent(
            clock.instant(),
            "system",
            action,
            "identity",
            link.source().value() + ":" + link.sourceUserId(),
            Map.of("userId", link.userId(), "method", "auto")));
  }

  private ReconcileReport plus(ReconcileReport left, ReconcileReport right) {
    return new ReconcileReport(
        left.linked() + right.linked(),
        left.unlinked() + right.unlinked(),
        left.unchanged() + right.unchanged(),
        left.failedSources() + right.failedSources());
  }
}

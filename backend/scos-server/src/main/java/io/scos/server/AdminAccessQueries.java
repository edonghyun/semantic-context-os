package io.scos.server;

import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.SourceQueries.AccessStatus;
import io.scos.engine.admin.AdminDiagnostics;
import io.scos.spi.access.AdminRecords.IdentityLink;
import io.scos.spi.access.AdminRecords.ObservedIdentity;
import io.scos.spi.access.AdminRecords.PageRequest;
import io.scos.spi.access.AdminStore;
import io.scos.spi.source.SourceInstanceId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** 기존 읽기 집계와 설명 결과를 관리 경계의 값으로 변환한다. */
final class AdminAccessQueries implements AdminDiagnostics {
  private final SourceQueries queries;
  private final AccessExplainer explainer;
  private final AdminStore store;
  private final Clock clock;

  AdminAccessQueries(
      SourceQueries queries, AccessExplainer explainer, AdminStore store, Clock clock) {
    this.queries = queries;
    this.explainer = explainer;
    this.store = store;
    this.clock = clock;
  }

  @Override
  public List<SourceAccessView> sourceAccess() {
    return queries.listSources().stream()
        .flatMap(
            source ->
                queries
                    .accessStatus(source.id())
                    .map(status -> sourceView(source, status))
                    .stream())
        .toList();
  }

  private SourceAccessView sourceView(SourceInstance source, AccessStatus status) {
    return new SourceAccessView(
        source.id(),
        source.kind(),
        queries
            .status(source.id(), clock.instant())
            .flatMap(SourceQueries.SourceStatus::lastRun)
            .map(run -> run.status().wire()),
        status.result().map(result -> result.wire()),
        status.finishedAt(),
        status.rawUnknown(),
        status.rawFailed(),
        status.documentsMissingAcl(),
        status.rawUnobserved(),
        status.rawStale(),
        status.oldestObservedAt(),
        status.running(),
        status.consecutiveIncomplete(),
        status.lastCompleteAt(),
        status.documentsHidden(),
        status.unreadableFiles(),
        status
            .cost()
            .map(cost -> new RunCost(cost.runId().value(), cost.recordedAt(), cost.cost())));
  }

  @Override
  public Optional<DocumentAccessView> explain(String documentId, Optional<String> userId) {
    return explainer
        .explain(documentId, userId)
        .map(
            result -> {
              var document = result.document();
              return new DocumentAccessView(
                  document.documentId(),
                  document.source(),
                  document.space(),
                  document.acl(),
                  document.carriers().stream()
                      .map(carrier -> new CarrierAccess(carrier.externalId(), carrier.acl()))
                      .toList(),
                  result
                      .user()
                      .map(
                          user ->
                              new UserAccessView(
                                  user.id(),
                                  user.known(),
                                  user.keys(),
                                  user.intersection(),
                                  user.visible(),
                                  links(user.id(), document.source()))));
            });
  }

  private IdentityLink withWarnings(IdentityLink link, List<ObservedIdentity> observations) {
    var observed =
        observations.stream()
            .map(ObservedIdentity::identity)
            .filter(identity -> identity.sourceUserId().equals(link.sourceUserId()))
            .findFirst();
    var warnings = new HashSet<String>();
    if (observed.isEmpty()) {
      warnings.add("unobserved");
    } else {
      if (observed.get().guest()) warnings.add("guest");
      if (observed.get().deleted()) warnings.add("deleted");
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

  private List<IdentityLink> links(String user, SourceInstanceId source) {
    return store.transact(
        session -> {
          var result = new ArrayList<IdentityLink>();
          var observations = session.observedIdentities(source);
          var initial = new PageRequest();
          var page = Optional.of(session.links(Optional.of(user), initial));
          while (page.isPresent()) {
            var current = page.get();
            current.items().stream()
                .filter(link -> link.source().equals(source))
                .map(link -> withWarnings(link, observations))
                .forEach(result::add);
            page =
                current
                    .nextCursor()
                    .map(
                        after ->
                            session.links(
                                Optional.of(user),
                                new PageRequest(initial.limit(), Optional.of(after))));
          }
          return List.copyOf(result);
        });
  }
}

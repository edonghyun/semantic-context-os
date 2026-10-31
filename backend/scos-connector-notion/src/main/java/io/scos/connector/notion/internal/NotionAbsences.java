package io.scos.connector.notion.internal;

import io.scos.engine.EventLog;
import io.scos.spi.connector.AbsenceVerification;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.CollectionState.AbsenceCandidate;
import io.scos.spi.connector.CollectionState.AbsenceStatus;
import io.scos.spi.connector.CollectionState.BodySummary;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** 전체 순회가 놓친 현재 객체를 복구하고 같은 후보 집합의 검증을 커밋한다. */
final class NotionAbsences {
  // 복구 중 후보가 계속 바뀌어도 원격 검증을 무한 반복하지 않는다.
  private static final int MAX_VERIFICATION_PASSES = 3;
  private static final Logger log = LoggerFactory.getLogger(NotionAbsences.class);
  private final NotionDataSources.Dependencies deps;

  NotionAbsences(NotionDataSources.Dependencies deps) {
    this.deps = deps;
  }

  void verify() {
    for (int attempt = 0; attempt < MAX_VERIFICATION_PASSES; attempt++) {
      var candidates = deps.input().state().absences(deps.input().cancel());
      if (candidates.status() != AbsenceStatus.AVAILABLE) {
        EventLog.warn(log, "notion.absence.deferred")
            .source(deps.input().source().id())
            .detail("reason", candidates.status().name())
            .log();
        return;
      }
      var exclusions = verifiedExclusions(candidates.items());
      if (exclusions.isEmpty()) return;
      var current = deps.input().state().absences(deps.input().cancel());
      if (!current.token().equals(candidates.token())) continue;
      deps.sink()
          .accept(
              new CollectionPage(
                  List.of(),
                  List.of(),
                  deps.state().checkpoint(),
                  List.of(),
                  Optional.of(
                      new AbsenceVerification(
                          deps.state().fingerprint(), candidates.token(), exclusions.get()))));
      return;
    }
  }

  private Optional<Set<String>> verifiedExclusions(List<AbsenceCandidate> candidates) {
    var sources = new NotionDataSources(deps);
    var scope = new NotionScope(deps.state(), deps.client(), deps.calls(), deps.token());
    var exclusions = new HashSet<String>();
    for (var candidate : candidates) {
      var work = target(candidate);
      if (work.isEmpty()) return Optional.empty();
      try {
        var value = retrieve(work.get());
        Optional<BodySummary> previous = Optional.empty();
        if (value.path("in_trash").asBoolean() && work.get() instanceof NotionWork.Page page)
          previous =
              Optional.ofNullable(
                  deps.input()
                      .state()
                      .bodies(deps.state().fingerprint(), List.of(page.id()), deps.input().cancel())
                      .get(page.id()));
        switch (scope.check(value, previous)) {
          case OUTSIDE -> exclusions.add(candidate.externalId());
          case INCLUDED -> {
            if (!sources.collect(work.get(), NotionPageRefresh.Path.FULL, scope))
              return Optional.empty();
          }
          case UNKNOWN -> {
            return Optional.empty();
          }
        }
      } catch (CallFailureException failure) {
        if (failure.failure().kind() == CallFailure.Kind.PERMISSION_DENIED) return Optional.empty();
        throw failure;
      }
    }
    return Optional.of(Set.copyOf(exclusions));
  }

  private Optional<NotionWork> target(AbsenceCandidate candidate) {
    return switch (candidate.resourceType()) {
      case "notion.page" -> Optional.of(new NotionWork.Page(candidate.externalId()));
      case "notion.database" ->
          Optional.of(
              new NotionWork.Database(
                  candidate
                      .externalId()
                      .substring(NotionMutations.Resource.DATABASE.prefix.length())));
      case "notion.data_source" ->
          Optional.of(
              new NotionWork.DataSource(
                  candidate
                      .externalId()
                      .substring(NotionMutations.Resource.DATA_SOURCE.prefix.length())));
      default -> Optional.empty();
    };
  }

  private JsonNode retrieve(NotionWork work) {
    var value =
        deps.calls()
            .required(
                work.operation(),
                timeout ->
                    switch (work) {
                      case NotionWork.Page page ->
                          deps.client().retrievePage(deps.token(), page.id(), timeout);
                      case NotionWork.Database database ->
                          deps.client().retrieveDatabase(deps.token(), database.id(), timeout);
                      case NotionWork.DataSource source ->
                          deps.client().retrieveDataSource(deps.token(), source.id(), timeout);
                      default ->
                          throw new NotionProtocolException(
                              "Unsupported Notion verification target");
                    });
    if (!value.path("id").asString().equals(work.id()))
      throw new NotionProtocolException("Notion returned the wrong verification target");
    return value;
  }
}

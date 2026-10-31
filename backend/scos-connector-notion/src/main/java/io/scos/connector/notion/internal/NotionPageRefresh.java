package io.scos.connector.notion.internal;

import io.scos.engine.support.call.WorkQueue;
import io.scos.spi.connector.BodyObservation;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.CollectionState.BodySummary;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.ReconciliationContext;
import io.scos.spi.connector.ReconciliationKind;
import io.scos.spi.connector.ReconciliationObservation;
import io.scos.spi.connector.SourceOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** 최신 페이지 상태와 해당 버전의 본문 완료를 확인한다. */
final class NotionPageRefresh {
  /** 편집 중인 페이지 하나가 수집 전체를 붙잡지 않도록 한 번에 세 번까지만 열거한다. */
  private static final int MAX_VERSION_ATTEMPTS = 3;

  enum Path {
    INCREMENTAL,
    FULL
  }

  record Dependencies(
      NotionApiClient client,
      NotionCalls calls,
      NotionMutations mutations,
      CollectionInput input,
      NotionReconciliationState progress,
      PageSink sink,
      String token) {}

  sealed interface Result {
    List<NotionWork> discovered();
  }

  record Completed(String version, List<NotionWork> discovered) implements Result {
    Completed {
      discovered = List.copyOf(discovered);
    }
  }

  enum Incompletion {
    UNVERIFIED,
    REJECTED
  }

  record Pending(List<NotionWork> discovered, Incompletion reason) implements Result {
    Pending(List<NotionWork> discovered) {
      this(discovered, Incompletion.UNVERIFIED);
    }

    Pending {
      discovered = List.copyOf(discovered);
    }
  }

  private final Dependencies deps;
  private final Map<String, BodyRead> active = new HashMap<>();

  NotionPageRefresh(Dependencies deps) {
    this.deps = deps;
  }

  Result refresh(String pageId, Path path, NotionScope scope) {
    return refresh(pageId, path, scope, MAX_VERSION_ATTEMPTS);
  }

  /** 활성 소속 증거 없는 Search 신호의 404는 이월한다. 실제 원본의 부재 검증은 별도로 남긴다. */
  Result refreshCandidate(String pageId, NotionScope scope) {
    if (scope.isRoot(pageId)) return refresh(pageId, Path.INCREMENTAL, scope);
    var previous =
        deps.input()
            .state()
            .bodies(deps.progress().fingerprint(), List.of(pageId), deps.input().cancel())
            .get(pageId);
    if (previous != null && previous.active() && previous.verifiedMembership())
      return refresh(pageId, Path.INCREMENTAL, scope);
    return deps.calls()
        .isolated(
            new NotionWork.Page(pageId),
            timeout -> deps.client().retrievePage(deps.token(), pageId, timeout))
        .fold(
            rejected -> new Pending(List.of(), Incompletion.REJECTED),
            page -> {
              if (!NotionObjects.sameId(page.path("id").asString(), pageId))
                throw new NotionProtocolException(NotionRootCollection.WRONG_PAGE);
              return refresh(page, Path.INCREMENTAL, scope, MAX_VERSION_ATTEMPTS);
            });
  }

  private Result refresh(String pageId, Path path, NotionScope scope, int attemptsRemaining) {
    var found = retrievePage(pageId, scope);
    if (found.isEmpty()) return new Pending(List.of(), Incompletion.REJECTED);
    return refresh(found.get(), path, scope, attemptsRemaining);
  }

  private Result refresh(JsonNode page, Path path, NotionScope scope, int attemptsRemaining) {
    deps.progress().observePage(page, path);
    var current = active.get(page.path("id").asString());
    BodyRead read =
        current != null
                && current.version.equals(page.path("last_edited_time").asString())
                && current.page.path("parent").equals(page.path("parent"))
                && current.page.path("in_trash").equals(page.path("in_trash"))
            ? current
            : new BodyRead(page, attemptsRemaining);
    if (current != null && current != read) current.successor = read;
    active.put(read.pageId, read);
    try {
      return read.collect(path, scope);
    } finally {
      if (read.result != null) active.remove(read.pageId, read);
    }
  }

  private Optional<JsonNode> retrievePage(String pageId, NotionScope scope) {
    Optional<JsonNode> found;
    if (scope.isRoot(pageId))
      found =
          Optional.of(
              deps.calls()
                  .required(
                      new NotionWork.Page(pageId),
                      timeout -> deps.client().retrievePage(deps.token(), pageId, timeout)));
    else
      found =
          deps.calls()
              .isolated(
                  new NotionWork.Page(pageId),
                  timeout -> deps.client().retrievePage(deps.token(), pageId, timeout))
              .fold(
                  rejected -> {
                    deps.sink()
                        .accept(
                            new CollectionPage(
                                List.of(), List.of(rejected), deps.progress().checkpoint()));
                    return Optional.empty();
                  },
                  Optional::of);
    found.ifPresent(
        page -> {
          if (!NotionObjects.sameId(page.path("id").asString(), pageId))
            throw new NotionProtocolException(NotionRootCollection.WRONG_PAGE);
        });
    return found;
  }

  private final class BodyRead {
    private final JsonNode page;
    private final String pageId;
    private final String version;
    private final String fingerprint = deps.progress().fingerprint();
    private final NotionReads reads =
        new NotionReads(deps.client(), deps.calls(), deps.mutations(), deps.token());
    private final List<NotionWork> discovered = new ArrayList<>();
    private WorkQueue<NotionWork.BlockChildren> work;
    private @Nullable SourceOrder order;
    private final int attemptsRemaining;
    private boolean started;
    private boolean bootstrapBody;
    private boolean finishing;
    private @Nullable Result result;
    private @Nullable BodyRead successor;
    private @Nullable Path path;

    BodyRead(JsonNode page, int attemptsRemaining) {
      this.attemptsRemaining = attemptsRemaining;
      this.page = page;
      this.pageId = page.path("id").asString();
      this.version = page.path("last_edited_time").asString();
      this.work = WorkQueue.of(List.of(new NotionWork.BlockChildren(pageId, Optional.empty())));
    }

    Result collect(Path path, NotionScope scope) {
      this.path = path;
      if (successor != null) {
        result = successor.collect(path, scope);
        return result;
      }
      if (result != null) return result;
      var summary =
          deps.input()
              .state()
              .bodies(fingerprint, List.of(pageId), deps.input().cancel())
              .get(pageId);
      if (!started) bootstrapBody = summary == null || summary.completion().isEmpty();
      var verdict = scope.check(page, Optional.ofNullable(summary));
      if (verdict == NotionScope.Verdict.OUTSIDE) {
        result = new Completed(version, List.of());
        return result;
      }
      if (verdict == NotionScope.Verdict.UNKNOWN) {
        result = new Pending(List.of());
        return result;
      }
      return collectIncluded(summary, path, scope);
    }

    private Result collectIncluded(@Nullable BodySummary summary, Path path, NotionScope scope) {
      var mutation = deps.mutations().page(page);
      if (mutation instanceof RawMutation.Delete deleted) return deletePage(deleted);
      if (finishing) return completionResult();
      if (!started && path == Path.INCREMENTAL && summary != null && summary.isCompleted(version)) {
        order = ((RawMutation.Upsert) mutation).item().sourceOrder().orElseThrow();
        observe(List.of(mutation), Set.of(), BodyObservation.Outcome.CONFIRMED);
        result = new Completed(version, deps.progress().bodyHandoff(pageId, version));
        return result;
      }
      if (!started) {
        order = ((RawMutation.Upsert) mutation).item().sourceOrder().orElseThrow();
        started = true;
        observe(List.of(mutation), Set.of(), BodyObservation.Outcome.ENUMERATING);
      }
      return enumerate(path, scope);
    }

    private Result deletePage(RawMutation.Delete mutation) {
      deps.progress().bodyFinished(pageId, version, List.of());
      deps.sink()
          .accept(
              new CollectionPage(
                  List.of(mutation),
                  List.of(),
                  deps.progress().checkpoint(),
                  List.of(
                      new BodyObservation(
                          fingerprint,
                          pageId,
                          mutation.sourceVersion().orElseThrow(),
                          mutation.sourceOrder().orElseThrow(),
                          Set.of(),
                          BodyObservation.Outcome.DELETED)),
                  Optional.empty(),
                  List.of(
                      new ReconciliationObservation(
                          fingerprint, pageId, ReconciliationKind.PAGE, comparisonContext()))));
      result = new Completed(version, List.of());
      return result;
    }

    private Result enumerate(Path path, NotionScope scope) {
      while (!work.items().isEmpty()) {
        if (successor != null) {
          result = successor.collect(path, scope);
          return result;
        }
        if (result != null) return result;
        var current = work.items().getFirst();
        Optional<NotionApiClient.ListPage> listed = readBlocks(current);
        if (listed.isEmpty()) {
          result = new Pending(discovered, Incompletion.REJECTED);
          return result;
        }
        var blocks = listed.get();
        var changed =
            blocks.results().stream()
                .map(block -> deps.mutations().block(current.blockId(), block))
                .toList();
        var ids =
            blocks.results().stream()
                .map(block -> NotionMutations.BLOCK_PREFIX + block.path("id").asString())
                .collect(Collectors.toSet());
        var continuation =
            blocks
                .nextCursor()
                .map(c -> new NotionWork.BlockChildren(current.blockId(), Optional.of(c)));
        work = work.afterHead(1, continuation.stream().toList(), descendants(blocks.results()));
        observe(changed, ids, BodyObservation.Outcome.ENUMERATING);
      }
      return finishEnumeration(path, scope);
    }

    private Optional<NotionApiClient.ListPage> readBlocks(NotionWork.BlockChildren current) {
      return reads
          .blockChildren(current)
          .fold(
              rejected -> {
                deps.sink()
                    .accept(
                        new CollectionPage(
                            List.of(), List.of(rejected), deps.progress().checkpoint()));
                return Optional.empty();
              },
              Optional::of);
    }

    private Result finishEnumeration(Path path, NotionScope scope) {

      if (successor != null) {
        result = successor.collect(path, scope);
        return result;
      }
      if (result != null) return result;
      var finished = retrievePage(pageId, scope);
      if (finished.isEmpty()) {
        result = new Pending(discovered, Incompletion.REJECTED);
        return result;
      }
      var latest = finished.get();
      if (!latest.path("last_edited_time").asString().equals(version)) {
        if (attemptsRemaining == 1) {
          result = new Pending(discovered);
          return result;
        }
        return refresh(pageId, path, scope, attemptsRemaining - 1);
      }
      finishing = true;
      observe(List.of(deps.mutations().page(latest)), Set.of(), BodyObservation.Outcome.COMPLETE);
      return completionResult();
    }

    private Result completionResult() {
      var completed =
          deps.input()
              .state()
              .bodies(fingerprint, List.of(pageId), deps.input().cancel())
              .get(pageId);
      result =
          completed != null && completed.isCompleted(version)
              ? new Completed(version, discovered)
              : new Pending(discovered);
      return result;
    }

    private List<NotionWork.BlockChildren> descendants(List<JsonNode> blocks) {
      var nested = new ArrayList<NotionWork.BlockChildren>();
      for (var block : blocks) {
        if (block.path("in_trash").asBoolean()) continue;
        String id = block.path("id").asString();
        switch (block.path("type").asString()) {
          case "child_page" -> discovered.add(new NotionWork.Page(id));
          case "child_database" -> discovered.add(new NotionWork.Database(id));
          default -> {
            if (block.path("has_children").asBoolean())
              nested.add(new NotionWork.BlockChildren(id, Optional.empty()));
          }
        }
      }
      return nested;
    }

    private ReconciliationContext comparisonContext() {
      // collectが本文観測より先に現在の経路を設定する。
      var context = deps.progress().reconciliation(page, Objects.requireNonNull(path));
      return bootstrapBody
          ? new ReconciliationContext(
              context.path(),
              context.successfulExploration(),
              context.editedAt(),
              true,
              context.gracePeriod())
          : context;
    }

    private void observe(
        List<RawMutation> changed, Set<String> ids, BodyObservation.Outcome outcome) {
      if (outcome == BodyObservation.Outcome.COMPLETE)
        deps.progress().bodyFinished(pageId, version, discovered);
      else if (outcome == BodyObservation.Outcome.ENUMERATING)
        deps.progress().recordBodyProgress(pageId, version, work.items());
      deps.sink()
          .accept(
              new CollectionPage(
                  changed,
                  List.of(),
                  deps.progress().checkpoint(),
                  List.of(
                      new BodyObservation(
                          fingerprint,
                          pageId,
                          version,
                          // ENUMERATING/CONFIRMEDの開始で供給者の順序が確定する。
                          Objects.requireNonNull(order),
                          ids,
                          outcome,
                          Optional.of(comparisonContext())))));
    }
  }
}

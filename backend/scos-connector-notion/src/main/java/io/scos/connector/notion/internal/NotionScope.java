package io.scos.connector.notion.internal;

import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CollectionState.BodySummary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** 현재 원격 부모 사슬이 설정 roots에 닿는지를 검증한다. */
final class NotionScope {
  /** 잘못된 부모 사슬이 API 예산을 독점하지 않게 한다. 초과는 범위 밖이 아니라 미확정이다. */
  private static final int MAX_PARENT_DEPTH = 64;

  enum Verdict {
    INCLUDED,
    OUTSIDE,
    UNKNOWN
  }

  private final Roots roots;
  private final NotionApiClient client;
  private final NotionCalls calls;
  private final String token;
  private final Optional<NotionReconciliationState> history;
  private final Map<String, JsonNode> ancestors = new HashMap<>();
  private long memoEpoch;

  NotionScope(Roots roots, NotionApiClient client, NotionCalls calls, String token) {
    this(roots, client, calls, token, Optional.empty());
  }

  NotionScope(
      NotionReconciliationState state, NotionApiClient client, NotionCalls calls, String token) {
    this(state.roots(), client, calls, token, Optional.of(state));
  }

  private NotionScope(
      Roots roots,
      NotionApiClient client,
      NotionCalls calls,
      String token,
      Optional<NotionReconciliationState> history) {
    this.roots = roots;
    this.client = client;
    this.calls = calls;
    this.token = token;
    this.history = history;
  }

  Verdict check(JsonNode candidate) {
    long epoch = history.map(NotionReconciliationState::scopeEpoch).orElse(0L);
    if (memoEpoch != epoch) {
      ancestors.clear();
      memoEpoch = epoch;
    }
    if (candidate.path("in_trash").asBoolean() && previouslyVerifiedContainer(candidate))
      return Verdict.INCLUDED;
    JsonNode current = candidate;
    var visited = new HashSet<String>();
    while (true) {
      if (isRoot(current.path("id").asString())) return Verdict.INCLUDED;
      if (visited.size() >= MAX_PARENT_DEPTH) return Verdict.UNKNOWN;
      if (!visited.add(current.path("object").asString() + ":" + current.path("id").asString()))
        return Verdict.UNKNOWN;
      JsonNode parent = current.path("parent");
      String type = parent.path("type").asString();
      String id = parent.path(type).asString();
      String key = type + ":" + id;
      if (ancestors.containsKey(key)) {
        current = ancestors.get(key);
        continue;
      }
      try {
        if (type.equals("workspace")) return Verdict.OUTSIDE;
        Optional<JsonNode> found = ancestor(type, id);
        if (found.isEmpty()) return Verdict.UNKNOWN;
        current = found.get();
        ancestors.put(key, current);
      } catch (CallFailureException failure) {
        if (failure.failure().kind() == CallFailure.Kind.PERMISSION_DENIED) return Verdict.UNKNOWN;
        throw failure;
      }
    }
  }

  private Optional<JsonNode> ancestor(String type, String id) {
    Optional<JsonNode> found =
        switch (type) {
          case "page_id" ->
              Optional.of(
                  calls.required(
                      "notion.scope.page", timeout -> client.retrievePage(token, id, timeout)));
          case "data_source_id" ->
              Optional.of(
                  calls.required(
                      "notion.scope.data_source",
                      timeout -> client.retrieveDataSource(token, id, timeout)));
          case "database_id" ->
              Optional.of(
                  calls.required(
                      "notion.scope.database",
                      timeout -> client.retrieveDatabase(token, id, timeout)));
          case "block_id" ->
              Optional.of(
                  calls.required(
                      "notion.scope.block", timeout -> client.retrieveBlock(token, id, timeout)));
          default -> Optional.empty();
        };
    found.ifPresent(
        value -> {
          if (!NotionObjects.sameId(value.path("id").asString(), id))
            throw new NotionProtocolException("Notion returned the wrong ancestor");
        });
    return found;
  }

  private boolean previouslyVerifiedContainer(JsonNode candidate) {
    String id = candidate.path("id").asString();
    return switch (candidate.path("object").asString()) {
      case "database" -> history.map(state -> state.databases().contains(id)).orElse(false);
      case "data_source" -> history.map(state -> state.dataSources().contains(id)).orElse(false);
      default -> false;
    };
  }

  Verdict check(JsonNode candidate, Optional<BodySummary> previous) {
    if (candidate.path("in_trash").asBoolean()
        && previous.map(BodySummary::verifiedMembership).orElse(false)) return Verdict.INCLUDED;
    return check(candidate);
  }

  boolean isRoot(String id) {
    return roots.rootPageIds().stream().anyMatch(root -> NotionObjects.sameId(root, id));
  }
}

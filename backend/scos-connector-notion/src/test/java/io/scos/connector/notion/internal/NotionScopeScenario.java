package io.scos.connector.notion.internal;

import static io.scos.connector.notion.internal.NotionFixtures.CHILD_PAGE;
import static io.scos.connector.notion.internal.NotionFixtures.DATA_SOURCE;

import io.scos.collection.internal.RawContentCapture;
import io.scos.connector.notion.NotionAssembly;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.support.FakeHttpServer.Response;
import java.net.HttpURLConnection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 현재 부모 사슬 검증의 가짜 HTTP·호출기 연결. */
final class NotionScopeScenario implements AutoCloseable {
  static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
  static final String ROOT = "root";
  final FakeNotionServer server = NotionAssembly.server();
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final NotionApiClient client =
      NotionAssembly.client(server.baseUrl(), NotionClientScenario.TIMEOUT);
  final CollectionInput input =
      new CollectionInput(
          NotionAssembly.instance("{\"rootPageIds\":[\"root\"]}", NOW).descriptor(),
          Optional.empty(),
          CollectionMode.INCREMENTAL,
          NotionAssembly.recordingExecutor(NotionClientScenario.TIMEOUT, clock, new ArrayList<>()),
          RawContentCapture.defaults(),
          CancellationToken.none());

  NotionScope scope() {
    return new NotionScope(
        NotionFixtures.roots(ROOT), client, new NotionCalls(input, clock), server.token());
  }

  NotionScope scopeWithUnrelatedContainerHistory() {
    var state = NotionCheckpointScenario.fresh();
    state.rememberDatabase("other-container", NOW);
    state.rememberDataSource("other-container", NOW);
    return new NotionScope(state, client, new NotionCalls(input, clock), server.token());
  }

  JsonNode pageJustBeyondParentDepth() {
    // 후보 자신과 조상 64개를 합쳐 root까지 65번 이동해야 한다.
    final int ancestorCount = 64;
    for (int depth = 0; depth < ancestorCount; depth++) {
      String next = depth + 1 == ancestorCount ? ROOT : "boundary-" + (depth + 1);
      metadata("/v1/pages/boundary-" + depth, page("boundary-" + depth, "page_id", next));
    }
    metadata("/v1/pages/" + ROOT, page(ROOT, "workspace", ""));
    return page(CHILD_PAGE, "page_id", "boundary-0");
  }

  NotionScope scopeForRoot(String root) {
    return new NotionScope(
        NotionFixtures.roots(root), client, new NotionCalls(input, clock), server.token());
  }

  JsonNode workspacePage() {
    return page("foreign", "workspace", "");
  }

  JsonNode rowUnderRoot() {
    metadata("/v1/data_sources/ds", object("data_source", DATA_SOURCE, "database_id", "db"));
    metadata("/v1/databases/db", object("database", "db", "page_id", ROOT));
    metadata("/v1/pages/" + ROOT, page(ROOT, "workspace", ""));
    return page("row", "data_source_id", DATA_SOURCE);
  }

  JsonNode orphanedPage() {
    server.respond(
        "GET",
        "/v1/pages/unreadable",
        new Response(
            HttpURLConnection.HTTP_NOT_FOUND,
            "{\"object\":\"error\",\"code\":\"object_not_found\"}",
            Map.of()));
    return page("orphan", "page_id", "unreadable");
  }

  JsonNode pageUnderBlock() {
    metadata("/v1/blocks/parent-block", object("block", "parent-block", "page_id", ROOT));
    metadata("/v1/pages/" + ROOT, page(ROOT, "workspace", ""));
    return page(CHILD_PAGE, "block_id", "parent-block");
  }

  JsonNode cyclicPage() {
    var fault =
        NotionFixtures.JSON.createObjectNode().put("method", "GET").put("path", "/v1/pages/a");
    var replies = fault.putArray("responses");
    for (var value : List.of(page("a", "page_id", "b"), page("a", "workspace", ""))) {
      var reply =
          replies
              .addObject()
              .put("status", HttpURLConnection.HTTP_OK)
              .put("text", value.toString());
      reply.putObject("headers");
    }
    server.fault(fault);
    metadata("/v1/pages/b", page("b", "page_id", "a"));
    return page(CHILD_PAGE, "page_id", "a");
  }

  JsonNode deeplyNestedPage() {
    final int excessiveDepth = 65; // 부모 검증의 64단계 안전 한도를 넘는 합성 사슬.
    for (int depth = 0; depth < excessiveDepth; depth++) {
      String next = depth + 1 == excessiveDepth ? ROOT : "deep-" + (depth + 1);
      metadata("/v1/pages/deep-" + depth, page("deep-" + depth, "page_id", next));
    }
    metadata("/v1/pages/" + ROOT, page(ROOT, "workspace", ""));
    return page(CHILD_PAGE, "page_id", "deep-0");
  }

  void sharedAncestorIsUnderRoot() {
    metadata("/v1/pages/ancestor", page("ancestor", "page_id", ROOT));
    metadata("/v1/pages/" + ROOT, page(ROOT, "workspace", ""));
  }

  long ancestorRequests() {
    return server.requests().stream().filter(r -> r.path().equals("/v1/pages/ancestor")).count();
  }

  JsonNode formerlyIncludedPage() {
    sharedAncestorIsUnderRoot();
    var child = page(CHILD_PAGE, "page_id", "ancestor");
    if (scope().check(child) != NotionScope.Verdict.INCLUDED)
      throw new IllegalStateException("given 준비 실패: 이전 탐색에서 범위 안이어야 한다");
    metadata("/v1/pages/ancestor", page("ancestor", "workspace", ""));
    return child;
  }

  JsonNode unknownTrash() {
    return ((ObjectNode) orphanedPage()).put("in_trash", true);
  }

  static ObjectNode page(String id, String parentType, String parentId) {
    return object("page", id, parentType, parentId);
  }

  static ObjectNode object(String kind, String id, String parentType, String parentId) {
    ObjectNode value = NotionFixtures.JSON.createObjectNode();
    value
        .put("object", kind)
        .put("id", id)
        .put("created_time", NOW.toString())
        .put("last_edited_time", NOW.toString())
        .put("in_trash", false);
    value.putObject("parent").put("type", parentType).put(parentType, parentId);
    if (kind.equals("database")) value.putArray("data_sources");
    if (kind.equals("block")) value.put("type", "paragraph").put("has_children", true);
    return value;
  }

  void metadata(String path, JsonNode value) {
    server.respond(
        "GET", path, new Response(HttpURLConnection.HTTP_OK, value.toString(), Map.of()));
  }

  @Override
  public void close() {
    server.close();
  }
}

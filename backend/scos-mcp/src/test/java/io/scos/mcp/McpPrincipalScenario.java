package io.scos.mcp;

import static io.scos.spi.store.ContextUnits.unit;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.scos.engine.ContextEngine;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.RecordedSourceFreshness;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.TestExecutors;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** 실제 제한 엔진에서 local과 alice가 서로 다른 두 문서씩 보는 전송 명세 문맥. */
final class McpPrincipalScenario {
  static final Principal ALICE = new Principal("alice");
  private static final SourceInstanceId SOURCE = new SourceInstanceId("slack-a");
  private static final Instant OBSERVED = Instant.parse("2026-10-25T00:00:00Z");
  private static final int DIMENSIONS = 2;
  private final MemoryAclStore acl = new MemoryAclStore();
  private final MemoryUnitStore units = new MemoryUnitStore(acl);
  private final MemoryVectorIndex vectors = new MemoryVectorIndex(acl);
  private final MemoryKeywordIndex keywords = new MemoryKeywordIndex(acl);
  private final MemoryUnitMaintenance maintenance = new MemoryUnitMaintenance(units, vectors);
  private final McpTools tools;

  McpPrincipalScenario() {
    this(QueryLog.NONE);
  }

  McpPrincipalScenario(QueryLog queryLog) {
    var identities = new MemoryIdentityStore();
    identities.replace(
        new MemoryIdentityStore.Directory(
            List.of(user("alice"), user("local")),
            List.of(identity("alice"), identity("local")),
            List.of(link("alice"), link("local")),
            List.of(),
            Set.of()));
    load("alice");
    load("local");
    tools =
        new McpTools(
            ContextEngine.builder()
                .units(units)
                .keywords(keywords)
                .vectors(vectors)
                .access(
                    identities, new RecordedSourceFreshness(), new DomainPolicy(Set.of(), Map.of()))
                .embedder(FixedEmbedder.zero(DIMENSIONS))
                .lexicalExecutor(TestExecutors.CALLER_THREAD)
                .build(),
            JsonMapper.builder().build(),
            queryLog);
  }

  private MemoryIdentityStore.User user(String id) {
    return new MemoryIdentityStore.User(id, id + "@example.com", true);
  }

  private MemoryIdentityStore.Identity identity(String id) {
    return new MemoryIdentityStore.Identity(SOURCE, id, false, false);
  }

  private MemoryIdentityStore.Link link(String id) {
    return new MemoryIdentityStore.Link(SOURCE, id, id);
  }

  private void load(String user) {
    var docs =
        List.of(
            unit(user + "-guide")
                .inDocument(user + "-guide")
                .titled(user + "-guide")
                .content("정산 " + user)
                .linksTo(user + "-other")
                .build(),
            unit(user + "-other")
                .inDocument(user + "-other")
                .titled(user + "-other")
                .content("정산 " + user)
                .build());
    maintenance.upsert(SpaceId.LEGACY, docs);
    keywords.upsert(
        SpaceId.LEGACY,
        docs.stream()
            .map(
                u ->
                    new IndexedText(
                        u.id(), u.documentId(), u.title(), u.content(), u.partition(), false))
            .toList());
    acl.put(
        SpaceId.LEGACY,
        docs.stream()
            .map(
                u ->
                    new DocumentAcl(
                        u.documentId(),
                        DocumentAcl.Status.OK,
                        Set.of(new AccessKey(AccessKey.Kind.USER, SOURCE, user)),
                        OBSERVED))
            .toList());
  }

  CallToolResult stateless(String tool, Principal principal) {
    return call(tool, McpTransportContext.create(Map.of("scos.principal", principal)));
  }

  CallToolResult withoutPrincipal(String tool) {
    return call(tool, McpTransportContext.EMPTY);
  }

  CallToolResult withInvalidPrincipal(String tool) {
    return call(tool, McpTransportContext.create(Map.of(McpToolSpecs.PRINCIPAL_KEY, "alice")));
  }

  private CallToolResult call(String tool, McpTransportContext context) {
    return new McpToolSpecs(tools)
        .stateless(ErrorExposure.RAW).stream()
            .filter(spec -> spec.tool().name().equals(tool))
            .findFirst()
            .orElseThrow()
            .callHandler()
            .apply(context, request(tool, "alice"));
  }

  CallToolResult sync(String tool) {
    return McpToolSpecs.sync(tools).stream()
        .filter(spec -> spec.tool().name().equals(tool))
        .findFirst()
        .orElseThrow()
        .callHandler()
        .apply(null, request(tool, "local"));
  }

  private CallToolRequest request(String tool, String user) {
    Map<String, Object> args =
        tool.equals("read_document") || tool.equals("related_documents")
            ? Map.of("documentId", user + "-guide")
            : Map.of("query", "정산");
    return new CallToolRequest(tool, args);
  }
}

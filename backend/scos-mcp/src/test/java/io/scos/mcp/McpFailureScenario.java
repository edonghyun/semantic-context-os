package io.scos.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.scos.engine.ContextEngine;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.Principal;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 실제 엔진·계약 있는 어댑터를 조립하고 필요할 때만 임베더 실패를 주입한다. */
final class McpFailureScenario {
  private static final int DIMENSIONS = 2;
  private final McpTools tools;

  McpFailureScenario(Throwable failure) {
    this(new FailingEmbedder(failure));
  }

  static McpFailureScenario healthy() {
    return new McpFailureScenario(FixedEmbedder.zero(DIMENSIONS));
  }

  private McpFailureScenario(Embedder embedder) {
    var units = new MemoryUnitStore();
    var vectors = new MemoryVectorIndex();
    tools =
        new McpTools(
            ContextEngine.builder()
                .units(units)
                .unrestricted(new MemoryUnitMaintenance(units, vectors))
                .keywords(new MemoryKeywordIndex())
                .vectors(vectors)
                .embedder(embedder)
                .build(),
            JsonMapper.builder().build(),
            QueryLog.NONE);
  }

  CallToolResult call(ErrorExposure exposure) {
    return call("search_context", exposure);
  }

  CallToolResult call(String tool, ErrorExposure exposure) {
    return call(tool, Map.of("query", "정산"), exposure);
  }

  CallToolResult contractFailure(String tool) {
    Map<String, Object> args =
        tool.equals("read_document") ? Map.of("documentId", "secret-doc") : Map.of("query", "");
    return call(tool, args, ErrorExposure.MASKED);
  }

  private CallToolResult call(String tool, Map<String, Object> args, ErrorExposure exposure) {
    return new McpToolSpecs(tools)
        .stateless(exposure).stream()
            .filter(s -> s.tool().name().equals(tool))
            .findFirst()
            .orElseThrow()
            .callHandler()
            .apply(
                McpTransportContext.create(
                    Map.of(McpToolSpecs.PRINCIPAL_KEY, Principal.singleUser())),
                new CallToolRequest(tool, args));
  }

  private record FailingEmbedder(Throwable failure) implements Embedder {
    @Override
    public EmbedderIdentity identity() {
      return FixedEmbedder.zero(DIMENSIONS).identity();
    }

    @Override
    public List<double[]> embed(List<String> texts) {
      if (failure instanceof Error error) throw error;
      throw (RuntimeException) failure;
    }
  }
}

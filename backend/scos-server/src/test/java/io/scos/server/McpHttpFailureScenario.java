package io.scos.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.scos.engine.ContextEngine;
import io.scos.mcp.ErrorExposure;
import io.scos.mcp.McpToolSpecs;
import io.scos.mcp.McpTools;
import io.scos.mcp.QueryLog;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.Principal;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 엔드포인트와 SDK 서블릿을 통과시켜 실패 응답 바이트를 관찰한다. */
final class McpHttpFailureScenario implements AutoCloseable {
  private static final int DIMENSIONS = 2;
  private final McpHttpEndpoint endpoint;

  McpHttpFailureScenario(Throwable failure, ErrorExposure exposure) {
    var units = new MemoryUnitStore();
    var vectors = new MemoryVectorIndex();
    var engine =
        ContextEngine.builder()
            .units(units)
            .unrestricted(new MemoryUnitMaintenance(units, vectors))
            .keywords(new MemoryKeywordIndex())
            .vectors(vectors)
            .embedder(new FailingEmbedder(failure))
            .build();
    var tools = new McpTools(engine, JsonMapper.builder().build(), QueryLog.NONE);
    endpoint =
        McpHttpEndpoint.create(
            tools,
            "/mcp",
            exposure,
            request ->
                McpTransportContext.create(
                    Map.of(McpToolSpecs.PRINCIPAL_KEY, Principal.singleUser())),
            () -> {});
  }

  JsonNode call() throws Exception {
    var request = new MockHttpServletRequest("POST", "/mcp");
    request.addHeader("Accept", "application/json, text/event-stream");
    request.setContentType("application/json");
    request.setContent(
        """
        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_context","arguments":{"query":"정산"}}}
        """
            .getBytes(StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    endpoint.servlet().service(request, response);
    return JsonMapper.builder().build().readTree(response.getContentAsString());
  }

  @Override
  public void close() throws Exception {
    endpoint.close();
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

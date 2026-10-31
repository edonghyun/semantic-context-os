package io.scos.server;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.charset.StandardCharsets;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SDK 서버와 실제 서블릿 직렬화를 쓰는 예외 특성화 문맥. 외부 자원은 열지 않는다. */
final class McpSdkFailureScenario implements AutoCloseable {
  private final HttpServletStatelessServerTransport transport =
      HttpServletStatelessServerTransport.builder()
          .jsonMapper(McpJsonDefaults.getMapper())
          .messageEndpoint("/mcp")
          .build();
  private final McpStatelessSyncServer server;

  McpSdkFailureScenario(Throwable failure) {
    var tool =
        Tool.builder().name("failure").inputSchema(McpJsonDefaults.getMapper(), "{}").build();
    var spec =
        McpStatelessServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler(
                (context, request) -> {
                  if (failure instanceof Error error) throw error;
                  throw (RuntimeException) failure;
                })
            .build();
    server = McpServer.sync(transport).tools(spec).build();
  }

  JsonNode call() throws Exception {
    var request = new MockHttpServletRequest("POST", "/mcp");
    request.addHeader("Accept", "application/json, text/event-stream");
    request.setContentType("application/json");
    request.setContent(
        """
        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"failure","arguments":{}}}
        """
            .getBytes(StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    transport.service(request, response);
    return JsonMapper.builder().build().readTree(response.getContentAsString());
  }

  @Override
  public void close() {
    server.close();
  }
}

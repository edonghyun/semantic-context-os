package io.scos.server;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.scos.mcp.ErrorExposure;
import io.scos.mcp.McpToolSpecs;
import io.scos.mcp.McpTools;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;

/** 상태 없는 HTTP 전송 위의 MCP 서버. 닫을 때 읽기 풀 같은 자원도 닫는다. */
final class McpHttpEndpoint implements AutoCloseable {
  private final HttpServletStatelessServerTransport transport;
  private final McpStatelessSyncServer server;
  private final AutoCloseable resources;

  private McpHttpEndpoint(
      HttpServletStatelessServerTransport transport,
      McpStatelessSyncServer server,
      AutoCloseable resources) {
    this.transport = transport;
    this.server = server;
    this.resources = resources;
  }

  static McpHttpEndpoint create(
      McpTools tools,
      String path,
      ErrorExposure exposure,
      McpTransportContextExtractor<HttpServletRequest> contextExtractor,
      AutoCloseable resources) {
    HttpServletStatelessServerTransport transport =
        HttpServletStatelessServerTransport.builder()
            .jsonMapper(McpJsonDefaults.getMapper())
            .messageEndpoint(path)
            .contextExtractor(contextExtractor)
            .build();
    McpStatelessSyncServer server =
        McpServer.sync(transport)
            .serverInfo(McpToolSpecs.SERVER_NAME, McpToolSpecs.SERVER_VERSION)
            .instructions(McpToolSpecs.INSTRUCTIONS)
            .capabilities(McpToolSpecs.capabilities())
            .tools(new McpToolSpecs(tools).stateless(exposure))
            .build();
    return new McpHttpEndpoint(transport, server, resources);
  }

  HttpServlet servlet() {
    return transport;
  }

  @Override
  public void close() throws Exception {
    try {
      server.close();
    } finally {
      resources.close();
    }
  }
}

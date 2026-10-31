package io.scos.mcp;

import static org.assertj.core.api.Assertions.catchThrowable;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.scos.support.LogCapture;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** 오류 로그 캡처의 연결·해제를 소유하고 실제 도구 호출의 관찰만 돌려준다. */
final class McpFailureLogScenario implements AutoCloseable {
  private final McpFailureScenario failure;
  private final LogCapture logs = LogCapture.of(McpToolSpecs.class);

  McpFailureLogScenario(Throwable cause) {
    this(new McpFailureScenario(cause));
  }

  static McpFailureLogScenario healthy() {
    return new McpFailureLogScenario(McpFailureScenario.healthy());
  }

  private McpFailureLogScenario(McpFailureScenario failure) {
    this.failure = failure;
  }

  CallToolResult call() {
    return failure.call(ErrorExposure.MASKED);
  }

  CallToolResult call(String tool) {
    return failure.call(tool, ErrorExposure.MASKED);
  }

  CallToolResult contractFailure(String tool) {
    return failure.contractFailure(tool);
  }

  Throwable fail() {
    return catchThrowable(() -> failure.call(ErrorExposure.MASKED));
  }

  List<JsonNode> failures() {
    return logs.events("mcp.tool.failed");
  }

  List<JsonNode> rejections() {
    return logs.events("mcp.tool.rejected");
  }

  @Override
  public void close() {
    logs.close();
  }
}

package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.ContextEngine;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claude Code 같은 클라이언트는 initialize 응답의 instructions를 에이전트 시스템 프롬프트에 싣는다 — 도구가 이름만 보이는 지연 로딩 상태에서도
 * 보이는 서버 글이다. 안내 내용의 계약은 {@link McpToolSpecsTest}가 본다.
 */
@DisplayName("MCP stdio 서버는 initialize 응답에 도구 사용 안내를 싣는다")
class McpStdioInitializeTest {
  static final String INITIALIZE =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}";
  static final int INITIALIZE_ID = 1;
  static final int EMBEDDING_DIM = 2;
  static final JsonMapper JSON = JsonMapper.builder().build();

  final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
  McpStdioServer server;

  @AfterEach
  void stop() {
    if (server != null) server.stop();
  }

  static McpTools tools() {
    var units = new MemoryUnitStore();
    var vectors = new MemoryVectorIndex();
    return new McpTools(
        ContextEngine.builder()
            .units(units)
            .unrestricted(new MemoryUnitMaintenance(units, vectors))
            .keywords(new MemoryKeywordIndex())
            .vectors(vectors)
            .embedder(FixedEmbedder.zero(EMBEDDING_DIM))
            .build(),
        JSON,
        QueryLog.NONE);
  }

  static ByteArrayInputStream stdin(String line) {
    return new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8));
  }

  /** stdout에 쓴 initialize 응답의 result.instructions. 필드가 없으면 비어 있다. */
  Optional<String> initializeInstructions() {
    JsonNode result =
        stdout
            .toString(StandardCharsets.UTF_8)
            .lines()
            .map(JSON::readTree)
            .filter(m -> m.path("id").asInt() == INITIALIZE_ID)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("initialize 응답이 stdout에 없다"))
            .path("result");
    return Optional.ofNullable(result.get("instructions")).map(JsonNode::asString);
  }

  @Test
  @DisplayName("initialize 응답에 사용 안내를 싣는다")
  void answersInitializeWithInstructions() throws Exception {
    // given
    server = new McpStdioServer(tools(), McpServerProfile.ORGANIZATION, stdin(INITIALIZE), stdout);

    // when
    server.start();
    server.awaitStdinEof();

    // then
    assertThat(initializeInstructions())
        .hasValueSatisfying(text -> assertThat(text).contains("assemble_context"));
  }
}

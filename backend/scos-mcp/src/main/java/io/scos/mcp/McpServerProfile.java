package io.scos.mcp;

import java.util.Map;

/**
 * MCP 서버가 클라이언트에 보이는 글 — 이름·버전·안내문·도구 설명. 입력 스키마는 프로필과 무관하다(같은 와이어). {@code toolDescriptions}에서 빠진
 * 도구는 {@link #ORGANIZATION}의 설명을 쓴다.
 */
public record McpServerProfile(
    String name, String version, String instructions, Map<String, String> toolDescriptions) {

  private static final Map<String, String> DEFAULT_DESCRIPTIONS =
      Map.of(
          "search_context", McpToolSpecs.SEARCH_DESCRIPTION,
          "read_document", McpToolSpecs.READ_DESCRIPTION,
          "related_documents", McpToolSpecs.RELATED_DESCRIPTION,
          "assemble_context", McpToolSpecs.ASSEMBLE_DESCRIPTION);

  /** 사내 지식 서버(지금까지의 고정값). */
  public static final McpServerProfile ORGANIZATION =
      new McpServerProfile(
          McpToolSpecs.SERVER_NAME,
          McpToolSpecs.SERVER_VERSION,
          McpToolSpecs.INSTRUCTIONS,
          DEFAULT_DESCRIPTIONS);

  public McpServerProfile {
    toolDescriptions = Map.copyOf(toolDescriptions);
    for (String toolName : toolDescriptions.keySet()) {
      if (!DEFAULT_DESCRIPTIONS.containsKey(toolName))
        throw new IllegalArgumentException("unknown tool name: " + toolName);
    }
  }
}

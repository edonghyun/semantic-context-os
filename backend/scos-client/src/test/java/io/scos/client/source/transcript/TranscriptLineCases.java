package io.scos.client.source.transcript;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/** 명세 E1에 이름이 나온 종류와 텍스트 없는 사용자 블록의 합성 사례. */
public final class TranscriptLineCases {
  private TranscriptLineCases() {}

  public static Stream<Arguments> nonProjected() {
    return Stream.of(
        Arguments.of("attachment", TranscriptLines.attachment("합성 첨부").build()),
        Arguments.of("api-request-blob", TranscriptLines.apiRequestBlob("합성 요청").build()),
        Arguments.of("last-prompt", TranscriptLines.lastPrompt("합성 마지막 프롬프트").build()),
        Arguments.of("mode", TranscriptLines.mode("plan").build()),
        Arguments.of("system", TranscriptLines.system("합성 시스템 안내").build()),
        Arguments.of("file-history-snapshot", TranscriptLines.fileHistorySnapshot().build()),
        Arguments.of("user/tool_result", TranscriptLines.toolResult("합성 도구 출력").build()),
        Arguments.of(
            "user/image",
            TranscriptLines.user("")
                .blocks(
                    List.of(
                        Map.of(
                            "type",
                            "image",
                            "source",
                            Map.of(
                                "type",
                                "base64",
                                "media_type",
                                "image/png",
                                "data",
                                "iVBORw0KGgo="))))
                .build()));
  }

  public static Stream<Arguments> projected() {
    return Stream.of(
        Arguments.of("user/text", TranscriptLines.user("질문").build()),
        Arguments.of("assistant", TranscriptLines.assistant("답변").build()),
        Arguments.of("ai-title", TranscriptLines.aiTitle("제목").build()),
        Arguments.of(
            "assistant/tool_use",
            TranscriptLines.toolUse("Bash", Map.of("command", "pwd")).build()),
        Arguments.of(
            "user/answers",
            TranscriptLines.toolResult("합성 답변 원문").answers(Map.of("저장 위치?", "~/.scos")).build()),
        Arguments.of(
            "user/legacy-string",
            TranscriptLines.user("")
                .with("message", Map.of("role", "user", "content", "이전 형식 질문"))
                .build()));
  }
}

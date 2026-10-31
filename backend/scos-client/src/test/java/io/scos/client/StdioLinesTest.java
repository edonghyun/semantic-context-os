package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("stdio 지원은 입력 종료와 응답 줄 경계를 보존한다(TG-5.1, TG-5.5)")
class StdioLinesTest {
  private static final Duration WAIT = Duration.ofSeconds(2);

  @Test
  @DisplayName("입력을 종료하면 보낸 한글 줄 뒤에서 EOF를 읽는다")
  void inputEndsAfterSentLines() throws Exception {
    // given
    var input = new StdioLines.LineInput();
    input.send("한글");
    input.end();
    // when
    String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    // then
    assertThat(text).isEqualTo("한글\n");
  }

  @Test
  @DisplayName("출력은 완성한 줄과 아직 끝나지 않은 꼬리를 보존한다")
  void outputRetainsPartialLine() throws Exception {
    // given
    var output = new StdioLines.LineOutput();
    output.write("첫 줄\n꼬리".getBytes(StandardCharsets.UTF_8));
    // when
    List<String> lines = output.lines();
    // then
    assertThat(lines).containsExactly("첫 줄", "꼬리");
  }

  @Test
  @DisplayName("요청한 식별자와 다른 응답은 건너뛴다")
  void matchesResponseId() throws Exception {
    // given
    var output = new StdioLines.LineOutput();
    output.write("{\"id\":1}\n{\"id\":2,\"result\":\"합성 응답\"}\n".getBytes(StandardCharsets.UTF_8));
    // when
    var response = output.response(2, WAIT);
    // then
    assertThat(response.toString()).isEqualTo("{\"id\":2,\"result\":\"합성 응답\"}");
  }
}

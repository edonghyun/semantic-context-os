package io.scos.client.source.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("JSONL 읽기는 완전한 줄의 바이트 위치를 보존한다(ADR-0018)")
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JsonlReaderTest {
  @TempDir Path root;
  private JsonlScenario scenario;

  @BeforeEach
  void setUp() {
    scenario = new JsonlScenario(root.resolve("session.jsonl"));
  }

  @Test
  @DisplayName("빈 줄과 한글 줄의 끝 위치를 바이트로 센다")
  void keepsByteOffsets() throws IOException {
    // given
    scenario.givenText("\n가\r\n미완성");

    // when
    List<String> lines = scenario.read(0, 0);

    // then
    assertThat(lines).containsExactly("0@1:", "1@6:가\r");
  }

  @Test
  @DisplayName("재개 위치 이후의 절대 오프셋과 줄 번호를 돌려준다")
  void resumesAtAbsolutePosition() throws IOException {
    // given
    scenario.givenText("가\n나\n꼬리");

    // when
    List<String> lines = scenario.read(4, 7);

    // then
    assertThat(lines).containsExactly("7@8:나");
  }

  @Test
  @DisplayName("개행 없는 꼬리는 완전한 줄로 내지 않는다")
  void discardsIncompleteTail() throws IOException {
    // given
    scenario.givenText("미완성");

    // when
    List<String> lines = scenario.read(0, 0);

    // then
    assertThat(lines).isEmpty();
  }

  @Test
  @DisplayName("버퍼 끝의 개행 다음 줄도 빠뜨리지 않는다")
  void handlesNewlineAtBufferBoundary() throws IOException {
    // given
    scenario.givenText("x".repeat(65_535) + "\n나\n");

    // when
    List<String> lines = scenario.read(0, 0);

    // then
    assertThat(lines).containsExactly("0@65536:" + "x".repeat(65_535), "1@65540:나");
  }

  @Test
  @DisplayName("버퍼보다 긴 줄을 하나의 줄로 돌려준다")
  void joinsBufferFragments() throws IOException {
    // given
    scenario.givenText("가".repeat(30_000) + "\n끝\n");

    // when
    List<String> lines = scenario.read(0, 0);

    // then
    assertThat(lines).containsExactly("0@90001:" + "가".repeat(30_000), "1@90005:끝");
  }

  private record JsonlScenario(Path file) {
    void givenText(String text) throws IOException {
      Files.writeString(file, text);
    }

    List<String> read(long offset, long number) throws IOException {
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
        JsonlReader reader = new JsonlReader(channel, offset, number);
        List<String> result = new ArrayList<>();
        long previousEnd = offset;
        while (reader.hasNext()) {
          JsonlReader.Line line = reader.next();
          if (line.endOffset() <= previousEnd)
            throw new AssertionError("완전한 줄의 바이트 위치는 앞으로 진행해야 한다");
          previousEnd = line.endOffset();
          result.add(
              line.number()
                  + "@"
                  + line.endOffset()
                  + ":"
                  + new String(line.bytes(), StandardCharsets.UTF_8));
        }
        return result;
      }
    }
  }
}

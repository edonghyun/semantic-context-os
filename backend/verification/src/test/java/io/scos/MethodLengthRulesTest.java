package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 메서드 길이 규칙(TESTING §13.2): 제품 main 메서드·생성자 본문의 코드 줄은 {@link #LIMIT}줄 이하다. 넘는 기존 메서드는 줄 수와 함께 고정 목록
 * {@link #BASELINE}에 있고 그보다 길어지면 안 된다. 줄어들면 목록 갱신을 요구한다.
 */
@DisplayName("제품 main 메서드는 고정 목록 밖에서 40줄을 넘지 않는다(TESTING §13.2)")
class MethodLengthRulesTest {
  /** 본문 코드 줄 상한: 한 화면에 읽히는 길이(작업 지시 기본값 — 규약 문서에 따로 정한 수가 없다). */
  static final int LIMIT = 40;

  static final Path BASELINE =
      Path.of("verification/src/test/resources/method-length-baseline.txt");

  /** 열쇠("소스 경로 타입.이름(매개변수 타입)") → 본문 코드 줄 수. */
  static Map<String, Integer> productMethodLengths() throws IOException {
    Map<String, Integer> lengths = new LinkedHashMap<>();
    for (Path file : ProductSourceTree.files())
      MethodLengths.of(Files.readString(file))
          .forEach((signature, length) -> lengths.put(file + " " + signature, length));
    return lengths;
  }

  @Test
  @DisplayName("새로 넘는 메서드·길어진 메서드·갱신 안 된 목록 줄이 없다")
  void staysWithinBaseline() throws IOException {
    // given
    Map<String, Integer> current = productMethodLengths();
    MethodLengthBaseline baseline = MethodLengthBaseline.parse(Files.readString(BASELINE));
    if (Boolean.getBoolean("scos.freeze.update")) {
      baseline = baseline.updated(current, LIMIT);
      Files.writeString(BASELINE, baseline.format());
    }

    // when
    List<String> problems = baseline.problems(current, LIMIT);

    // then
    assertThat(problems).isEmpty();
  }
}

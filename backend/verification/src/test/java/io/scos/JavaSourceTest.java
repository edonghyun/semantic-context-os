package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Java 소스는 주석·문자열을 코드에서 지우고 줄 위치를 지킨다")
class JavaSourceTest {
  static final String SAMPLE = "a(\"{\"); // c\n/* d */ b('}');\n";

  @Test
  @DisplayName("문자열·문자 안의 중괄호는 코드에 남지 않는다")
  void blanksStringsAndChars() {
    // when
    JavaSource source = JavaSource.of(SAMPLE);

    // then
    assertThat(source.code()).doesNotContain("{", "}").hasSameSizeAs(SAMPLE);
  }

  @Test
  @DisplayName("줄 주석과 블록 주석을 원문 그대로 모은다")
  void collectsComments() {
    // when
    JavaSource source = JavaSource.of(SAMPLE);

    // then
    assertThat(source.commentsBetween(-1, SAMPLE.length())).containsExactly("// c", "/* d */");
  }

  @Test
  @DisplayName("코드의 여는 중괄호에 맞는 닫는 중괄호를 찾는다")
  void matchesBraces() {
    // when
    int close = JavaSource.of("x { y(\"}\") { } }").matchingBrace(2);

    // then
    assertThat(close).isEqualTo(15);
  }

  @Test
  @DisplayName("위치의 줄 번호는 1부터다")
  void linesStartAtOne() {
    // when
    int line = JavaSource.of(SAMPLE).lineOf(SAMPLE.indexOf("b("));

    // then
    assertThat(line).isEqualTo(2);
  }
}

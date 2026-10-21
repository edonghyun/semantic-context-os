package io.scos.collection.internal.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 자격 증명 값의 앞뒤 공백 떼기는 TS `String.prototype.trim()`과 같다. 떼는 문자는 Node로 BMP 전체를 훑어 얻은 목록이다 — Java
 * `strip()`과 달리 BOM(U+FEFF)과 NBSP(U+00A0)를 떼고, 정보 분리자(U+001C–U+001F)와 NEL(U+0085)은 남긴다.
 */
@DisplayName("자격 증명 값의 앞뒤 공백 떼기는 TS String.trim()과 같다")
class CredentialTextTest {
  static final String TOKEN = "xoxb-token";

  @ParameterizedTest(name = "U+{0}")
  @ValueSource(
      strings = {
        "0009", "000A", "000B", "000C", "000D", "0020", "00A0", "1680", "2000", "2001", "2002",
        "2003", "2004", "2005", "2006", "2007", "2008", "2009", "200A", "2028", "2029", "202F",
        "205F", "3000", "FEFF"
      })
  @DisplayName("TS trim이 떼는 문자는 앞뒤에서 뗀다")
  void trimsJsWhitespace(String hex) {
    // given
    String c = Character.toString(Integer.parseInt(hex, 16));

    // when
    String trimmed = CredentialText.trim(c + TOKEN + c);

    // then
    assertThat(trimmed).isEqualTo(TOKEN);
  }

  @ParameterizedTest(name = "U+{0}")
  @ValueSource(strings = {"001C", "001F", "0085"})
  @DisplayName("TS trim이 남기는 문자는 남긴다")
  void keepsNonJsWhitespace(String hex) {
    // given
    String c = Character.toString(Integer.parseInt(hex, 16));

    // when
    String trimmed = CredentialText.trim(c + TOKEN);

    // then
    assertThat(trimmed).isEqualTo(c + TOKEN);
  }
}

package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("토큰 해시는 원문의 SHA-256을 저장한다")
class TokenHashTest {
  @Test
  @DisplayName("접두사가 없는 입력도 알려진 SHA-256으로 바꾼다")
  void hashesAnyPresentedValue() {
    var hash = TokenHash.of("abc");

    // then
    assertThat(hash.value())
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  @DisplayName("문자열 표현에는 해시 앞 여덟 자리만 보인다")
  void abbreviatesDiagnosticHash() {
    var hash = TokenHash.of("abc");

    // then
    assertThat(hash.toString()).isEqualTo("TokenHash[ba7816bf]");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "not-a-digest",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
      })
  @DisplayName("해시 값은 소문자 SHA-256만 받고 잘못된 값을 노출하지 않는다")
  void rejectsInvalidStoredDigest(String value) {
    // when·then
    assertThatThrownBy(() -> new TokenHash(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid token hash");
  }
}

package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("발급 토큰은 주입한 난수로 만들고 원문 노출을 제한한다")
class IssuedTokenTest {
  // 256개의 1비트는 6비트 42자(_)와 남은 4비트+00(8)로 인코딩된다. 실제 발급 비밀값이 아닌 고정 입력이다.
  private static final String ALL_ONES_TOKEN = "scos_" + "_".repeat(42) + "8";

  @Test
  @DisplayName("주입한 32바이트를 패딩 없는 base64url로 인코딩한다")
  void encodesInjectedRandomBytes() {
    var token = IssuedToken.generate(new FixedRandom());

    // then
    assertThat(token.value()).isEqualTo(ALL_ONES_TOKEN);
  }

  @Test
  @DisplayName("발급한 원문과 같은 해시를 돌려준다")
  void hashesIssuedValue() {
    var token = IssuedToken.generate(new FixedRandom());

    // then
    assertThat(token.hash()).isEqualTo(TokenHash.of(token.value()));
  }

  @Test
  @DisplayName("문자열 표현은 원문 대신 해시 앞 여덟 자리만 담는다")
  void hidesPresentedValue() {
    var token = IssuedToken.generate(new FixedRandom());

    // then
    assertThat(token.toString()).isEqualTo("scos_…aa815fae");
  }

  private static final class FixedRandom extends SecureRandom {
    private static final long serialVersionUID = 1L;
    private static final int TOKEN_BYTES = 32;

    @Override
    public void nextBytes(byte[] bytes) {
      if (bytes.length != TOKEN_BYTES)
        throw new IllegalStateException("given 준비 실패: 토큰 난수는 32바이트여야 한다");
      Arrays.fill(bytes, (byte) 0xff);
    }
  }
}

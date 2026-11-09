package io.scos.engine.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리 발급 응답의 문자열은 비밀 원문을 숨긴다(§7.2)")
class IssuedTokenTest {
  @Test
  @DisplayName("문자열로 바꾸면 토큰 식별자만 보이고 원문은 가려진다")
  void stringRepresentationRedactsSecret() {
    // given
    var token =
        new IssuedToken(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "scos_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG");

    // when
    var displayed = token.toString();

    // then
    assertThat(displayed)
        .isEqualTo("IssuedToken[tokenId=00000000-0000-0000-0000-000000000001, value=redacted]");
  }
}

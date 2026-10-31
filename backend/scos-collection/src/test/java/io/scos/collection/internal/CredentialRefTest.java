package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("credentialRef는 첫 콜론 앞의 스킴 이름으로 나뉜다(ADR-0022 §2)")
class CredentialRefTest {
  @Test
  @DisplayName("참조에 콜론이 있어도 첫 콜론에서만 나눈다")
  void splitsAtFirstColon() {
    // when
    Optional<CredentialRef> ref = CredentialRef.parse("command:op read op://v/i:f");

    // then
    assertThat(ref).contains(new CredentialRef("command", "op read op://v/i:f"));
  }

  @Test
  @DisplayName("첫 콜론 앞이 스킴 이름 모양이 아니면 나누지 않는다")
  void rejectsPastedBasicCredential() {
    // when
    Optional<CredentialRef> ref = CredentialRef.parse("me@example.com:token");

    // then
    assertThat(ref).isEmpty();
  }

  @Test
  @DisplayName("문자열로 보일 때 참조를 싣지 않는다")
  void redactsReference() {
    // when
    String shown = new CredentialRef("env", "SECRET_TOKEN").toString();

    // then
    assertThat(shown).isEqualTo("CredentialRef[env:…]");
  }
}

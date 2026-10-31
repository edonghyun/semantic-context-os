package io.scos.engine.internal.access;

import static io.scos.engine.internal.access.AccessResolutionScenario.MEMBER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessKey;
import io.scos.spi.store.AccessScope.Restricted;
import io.scos.spi.store.Principal;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("접근 범위 해석기는 매 요청의 신원 키를 제한 범위로 바꾼다")
class AccessResolverTest {
  @Test
  @DisplayName("활성 사용자는 저장소의 키로 Restricted가 된다")
  void activeUserGetsStoredKeys() {
    var scenario = new AccessResolutionScenario();
    scenario.registerMember();

    var scope = scenario.resolve(MEMBER);

    // then
    assertThat(scope)
        .isEqualTo(
            new Restricted(
                MEMBER,
                Set.of(
                    AccessKey.parse("domain:drive-a:example.com"),
                    AccessKey.parse("scope:notion-a:*"))));
  }

  @Test
  @DisplayName("모르는 사용자는 빈 Restricted다")
  void unknownUserGetsEmptyRestricted() {
    var scenario = new AccessResolutionScenario();
    scenario.registerMember();
    var unknown = new Principal("unknown");

    var scope = scenario.resolve(unknown);

    // then
    assertThat(scope).isEqualTo(new Restricted(unknown, Set.of()));
  }

  @Test
  @DisplayName("저장소 오류는 빈 범위로 삼키지 않고 전파한다")
  void lookupFailureIsPropagated() {
    var failure = new IllegalStateException("identity lookup unavailable");
    var resolver = AccessResolutionScenario.unavailableResolver(failure);

    // when·then
    assertThatThrownBy(() -> resolver.resolve(MEMBER)).isSameAs(failure);
  }

  @Test
  @DisplayName("단일 사용자 principal도 등록되지 않았으면 빈 Restricted다")
  void localPrincipalIsNotPrivileged() {
    var scenario = new AccessResolutionScenario();
    scenario.registerMember();

    var scope = scenario.resolve(Principal.singleUser());

    // then
    assertThat(scope).isEqualTo(new Restricted(Principal.singleUser(), Set.of()));
  }

  @Test
  @DisplayName("앞선 요청 뒤 비활성화된 사용자는 다음 요청에 키를 잃는다")
  void eachRequestReadsCurrentIdentity() {
    var scenario = new AccessResolutionScenario();
    scenario.registerMember();
    scenario.deactivateAfterRequest();

    var scope = scenario.resolve(MEMBER);

    // then
    assertThat(scope).isEqualTo(new Restricted(MEMBER, Set.of()));
  }
}

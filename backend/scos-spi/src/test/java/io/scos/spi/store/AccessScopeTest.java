package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessKey;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("접근 범위는 무제한과 제한을 명시적으로 구분한다(접근 설계 §4.1)")
class AccessScopeTest {
  private static final Principal ALICE = new Principal("alice");
  private static final AccessKey ALICE_KEY = AccessKey.parse("user:slack-a:U1");

  @Test
  @DisplayName("Restricted의 키 집합은 복사되어 바깥 변경이 보이지 않는다")
  void copiesKeys() {
    // given
    Set<AccessKey> keys = new HashSet<>(Set.of(ALICE_KEY));
    AccessScope.Restricted scope = new AccessScope.Restricted(ALICE, keys);
    // when
    keys.clear();
    // then
    assertThat(scope.keys()).containsExactly(ALICE_KEY);
  }

  @Test
  @DisplayName("빈 키의 Restricted는 Unrestricted와 같지 않다")
  void emptyKeysRemainRestricted() {
    // when
    AccessScope scope = new AccessScope.Restricted(ALICE, Set.of());
    // then
    assertThat(scope).isNotEqualTo(new AccessScope.Unrestricted(ALICE));
  }

  @Test
  @DisplayName("singleUser는 단일 사용자 principal의 Unrestricted다")
  void singleUserIsUnrestricted() {
    // when
    AccessScope scope = AccessScope.singleUser();
    // then
    assertThat(scope).isEqualTo(new AccessScope.Unrestricted(Principal.singleUser()));
  }

  @Test
  @DisplayName("Unrestricted는 principal 없이는 만들 수 없다")
  void unrestrictedRequiresPrincipal() {
    // when·then
    assertThatThrownBy(() -> new AccessScope.Unrestricted(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("principal");
  }

  @Test
  @DisplayName("Restricted는 principal 없이는 만들 수 없다")
  void restrictedRequiresPrincipal() {
    // when·then
    assertThatThrownBy(() -> new AccessScope.Restricted(null, Set.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("principal");
  }

  @Test
  @DisplayName("Restricted에서 꺼낸 키 집합은 바꿀 수 없다")
  void keysCannotBeChanged() {
    // given
    AccessScope.Restricted scope = new AccessScope.Restricted(ALICE, Set.of(ALICE_KEY));
    // when·then
    assertThatThrownBy(() -> scope.keys().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

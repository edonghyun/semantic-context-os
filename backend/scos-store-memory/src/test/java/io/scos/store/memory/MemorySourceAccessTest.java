package io.scos.store.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.source.SourceInstanceId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("메모리 원천 접근 교체는 디렉터리의 다른 상태를 보존한다")
class MemorySourceAccessTest {
  @Test
  @DisplayName("원천 교체 뒤 사용자·연결·조직 지정과 다른 원천 그룹을 유지한다")
  void replacementPreservesDirectory() {
    var scenario = new Scenario();
    scenario.replaceSource();
    // then
    assertThat(scenario.keys())
        .containsExactlyInAnyOrder(
            "user:src:U1", "user:other:U2", "group:src:C2", "group:other:G1", "scope:scoped:*");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("다른 원천의 신원이나 그룹이 섞인 교체는 거부한다")
  void replacementRejectsForeignAccess(boolean identity) {
    var scenario = new Scenario();
    // when·then
    assertThatThrownBy(() -> scenario.replaceForeignAccess(identity))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static final class Scenario {
    private final MemoryIdentityStore store = new MemoryIdentityStore();
    private final SourceInstanceId source = new SourceInstanceId("src");
    private final SourceInstanceId other = new SourceInstanceId("other");

    Scenario() {
      var directory =
          AccessDirectory.empty()
              .withUser(new AccessDirectory.User("local", "local@example.com", true))
              .withIdentity(new AccessDirectory.Identity(source, "U1", false, false))
              .withIdentity(new AccessDirectory.Identity(other, "U2", false, false))
              .withLink(new AccessDirectory.Link(source, "U1", "local", "admin"))
              .withLink(new AccessDirectory.Link(other, "U2", "local", "admin"))
              .withGroup(new AccessDirectory.Group(source, "C1", Set.of("U1")))
              .withGroup(new AccessDirectory.Group(other, "G1", Set.of("U2")))
              .withScope(new SourceInstanceId("scoped"));
      new MemoryIdentitySeed(store).seed(directory);
    }

    void replaceSource() {
      store.replaceSourceAccess(
          source,
          List.of(new MemoryIdentityStore.Identity(source, "U1", false, false)),
          List.of(new MemoryIdentityStore.Group(source, "C2", Set.of("U1"))));
    }

    void replaceForeignAccess(boolean identity) {
      store.replaceSourceAccess(
          source,
          List.of(new MemoryIdentityStore.Identity(identity ? other : source, "U1", false, false)),
          List.of(new MemoryIdentityStore.Group(identity ? source : other, "C2", Set.of("U1"))));
    }

    Set<String> keys() {
      return store
          .accessKeysOf("local", new DomainPolicy(Set.of(), Map.of()))
          .orElseThrow()
          .stream()
          .map(Object::toString)
          .collect(Collectors.toSet());
    }
  }
}

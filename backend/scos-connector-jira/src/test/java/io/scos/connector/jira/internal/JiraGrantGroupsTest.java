package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.spi.source.SourceInstanceId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Jira grant 그룹은 최종 사용자 집합에 키를 바인딩한다 (T-b16, 설계 §4.4)")
class JiraGrantGroupsTest {
  private final JiraGrantGroups groups = new JiraGrantGroups(new SourceInstanceId("jira-a"));

  @Test
  @DisplayName("같은 식별자와 사용자 집합이어도 project와 level 키는 구별된다")
  void grantGroupNamespacesAreDistinct() {
    // given
    var project = groups.project("1", List.of("U2"), JiraAccessScenario.NOW);
    // when
    var level = groups.level("1", List.of("U2"), JiraAccessScenario.NOW);
    // then
    assertThat(level.key()).isNotEqualTo(project.key());
  }

  @Test
  @DisplayName("정렬한 JSON 사용자 집합의 고정 SHA-256을 사용한다")
  void grantGroupDigestUsesCanonicalFinalUsers() {
    // when
    var grant = groups.project("P1", List.of("U2"), JiraAccessScenario.NOW);
    // then
    assertThat(grant.key().toString())
        .isEqualTo(
            "group:jira-a:~project%3AP1%3Ac28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6");
  }

  @Test
  @DisplayName("사용자 순서가 달라도 같은 키를 만든다")
  void grantGroupDigestIgnoresUserOrder() {
    // when
    var grant = groups.project("P1", List.of("U2", "U1"), JiraAccessScenario.NOW);
    // then
    assertThat(grant.key().value())
        .isEqualTo("~project:P1:e45f76f543e5d9f76f69376b3d417da93faf7c0fafb82e0f6a55986214ae9a03");
  }

  @Test
  @DisplayName("중복 사용자는 한 번만 hash에 포함한다")
  void grantGroupDigestIgnoresDuplicates() {
    // when
    var grant = groups.level("L1", List.of("U2", "U2"), JiraAccessScenario.NOW);
    // then
    assertThat(grant.key().value())
        .isEqualTo("~level:L1:c28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6");
  }

  @Test
  @DisplayName("제어 문자·따옴표·Unicode를 모호하지 않게 인코딩한다")
  void grantGroupDigestEscapesJsonUnambiguously() {
    // when
    var grant = groups.project("P1", List.of("a\"\\\b\t\n\f\r\u0001/한😀"), JiraAccessScenario.NOW);
    // then
    assertThat(grant.key().value())
        .isEqualTo("~project:P1:5cf0112f67e6ff8bdb4e80e09d40c1141d0bcb6a191ae74f78c82eca2ad7515a");
  }

  @ParameterizedTest
  @ValueSource(strings = {"\ud800", "\udc00", "a\ud800b"})
  @DisplayName("짝 없는 surrogate는 실패시킨다")
  void grantGroupRejectsUnpairedSurrogate(String user) {
    // when
    var failure = catchThrowable(() -> groups.project("P1", List.of(user), JiraAccessScenario.NOW));
    // then
    assertThat(failure)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid Jira accountId Unicode");
  }

  @Test
  @DisplayName("키와 snapshot은 같은 사용자 집합에 바인딩된다")
  void boundKeyAndSnapshotHaveSameFinalUsers() {
    // when
    var grant = groups.project("P1", List.of("U2", "U2"), JiraAccessScenario.NOW);
    // then
    assertThat(grant.snapshot().members()).isEqualTo(Set.of("U2"));
    assertThat(grant.snapshot().groupId())
        .isEqualTo("~project:P1:c28affbb6e9c6f5cd989cb8b8fb33b2e1c46459a65841b4fc786056f0d5c18c6");
  }
}

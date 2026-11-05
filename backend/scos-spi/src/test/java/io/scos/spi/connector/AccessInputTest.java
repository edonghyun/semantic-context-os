package io.scos.spi.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceIdentityRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("접근 수집 SPI는 불변 관측값과 명시적인 미구현 기본값을 제공한다")
class AccessInputTest {
  private static final Instant OBSERVED = Instant.parse("2026-10-14T00:00:00Z");
  private final AccessInputScenario scenario = new AccessInputScenario();

  @Test
  @DisplayName("호출자가 대상 목록을 바꿔도 접근 입력은 유지된다")
  void collectionsAreImmutable() {
    // given
    var externalIds = new ArrayList<>(List.of("C1:1700000000.000001"));
    AccessInput input = scenario.input(externalIds);

    // when
    externalIds.clear();

    // then
    assertThat(input.externalIds()).containsExactly("C1:1700000000.000001");
  }

  @Test
  @DisplayName("접근 입력의 대상 목록은 수정할 수 없다")
  void targetsCannotBeModified() {
    // given
    AccessInput input = scenario.input(new ArrayList<>(List.of("C1:1700000000.000001")));

    // when·then
    assertThatThrownBy(() -> input.externalIds().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("호출자가 ACL 맵을 바꿔도 관측 페이지는 유지된다")
  void pageCopiesAcls() {
    // given
    var acls = new HashMap<String, RawAcl>(Map.of("C1:1700000000.000001", RawAcl.notObserved()));
    var page = new AclPage(acls, List.of(), List.of());

    // when
    acls.clear();

    // then
    assertThat(page.acls()).containsOnlyKeys("C1:1700000000.000001");
  }

  @Test
  @DisplayName("호출자가 그룹 목록을 바꿔도 관측 페이지는 유지된다")
  void pageCopiesGroups() {
    // given
    var groups = new ArrayList<>(List.of(new GroupSnapshot("C1", Set.of("U1"), OBSERVED)));
    var page = new AclPage(Map.of(), groups, List.of());

    // when
    groups.clear();

    // then
    assertThat(page.groups()).containsExactly(new GroupSnapshot("C1", Set.of("U1"), OBSERVED));
  }

  @Test
  @DisplayName("호출자가 신원 목록을 바꿔도 관측 페이지는 유지된다")
  void pageCopiesIdentities() {
    // given
    var identities = new ArrayList<>(List.of(identity()));
    var page = new AclPage(Map.of(), List.of(), identities);

    // when
    identities.clear();

    // then
    assertThat(page.identities()).containsExactly(identity());
  }

  @Test
  @DisplayName("관측 페이지의 ACL 맵은 수정할 수 없다")
  void pageAclsCannotBeModified() {
    // given
    var page = new AclPage(new HashMap<>(), List.of(), List.of());

    // when·then
    assertThatThrownBy(() -> page.acls().put("C1:1700000000.000001", RawAcl.notObserved()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("관측 페이지의 그룹 목록은 수정할 수 없다")
  void pageGroupsCannotBeModified() {
    // given
    var page = new AclPage(Map.of(), new ArrayList<>(), List.of());

    // when·then
    assertThatThrownBy(() -> page.groups().add(new GroupSnapshot("C1", Set.of("U1"), OBSERVED)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("관측 페이지의 신원 목록은 수정할 수 없다")
  void pageIdentitiesCannotBeModified() {
    // given
    var page = new AclPage(Map.of(), List.of(), new ArrayList<>());

    // when·then
    assertThatThrownBy(() -> page.identities().add(identity()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("호출자가 구성원을 바꿔도 그룹 관측은 유지된다")
  void groupCopiesMembers() {
    // given
    var members = new HashSet<>(Set.of("U1"));
    var group = new GroupSnapshot("C1", members, OBSERVED);

    // when
    members.clear();

    // then
    assertThat(group.members()).containsExactly("U1");
  }

  @Test
  @DisplayName("그룹 관측의 구성원 집합은 수정할 수 없다")
  void groupMembersCannotBeModified() {
    // given
    var group = new GroupSnapshot("C1", new HashSet<>(Set.of("U1")), OBSERVED);

    // when·then
    assertThatThrownBy(() -> group.members().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("기본 접근 수집기는 완료를 보고할 수 없다")
  void defaultCollectorCannotReportCompletion() {
    // when·then
    assertThatThrownBy(scenario::collectWithDefault)
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("기본 접근 모델은 지원 원천으로서 수집 구현을 요구한다")
  void defaultAccessModelRequiresCollector() {
    // when
    AccessModel model = scenario.defaultAccessModel();

    // then
    assertThat(model).isEqualTo(AccessModel.SUPPORTED);
  }

  @Test
  @DisplayName("기본 어댑터는 권한 운반 종류를 추정하지 않는다")
  void defaultCarrierKindsAreEmpty() {
    // when
    Set<String> kinds = scenario.defaultCarrierKinds();

    // then
    assertThat(kinds).isEmpty();
  }

  @Test
  @DisplayName("기본 어댑터는 외부 식별자로 ACL을 추정하지 않는다")
  void defaultExternalIdAclIsEmpty() {
    // when
    Optional<RawAcl> acl = scenario.defaultAcl(OBSERVED);

    // then
    assertThat(acl).isEmpty();
  }

  private SourceIdentityRecord identity() {
    return new SourceIdentityRecord(
        "U1", Optional.of("member@example.com"), true, false, false, OBSERVED);
  }
}

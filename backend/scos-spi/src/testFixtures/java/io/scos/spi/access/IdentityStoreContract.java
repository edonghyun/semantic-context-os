package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessDirectory.Group;
import io.scos.spi.access.AccessDirectory.Identity;
import io.scos.spi.access.AccessDirectory.Link;
import io.scos.spi.access.AccessDirectory.User;
import io.scos.spi.source.SourceInstanceId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 사용자 접근 키를 계산하는 저장소의 공통 계약. */
@DisplayName("신원 저장소는 활성 사용자에게만 원천별 접근 키를 준다")
public abstract class IdentityStoreContract {
  protected static final String MEMBER_ID = "member";
  protected static final User MEMBER = new User(MEMBER_ID, "member@example.com", true);
  protected static final DomainPolicy NO_DOMAINS = new DomainPolicy(Set.of(), Map.of());
  protected static final SourceInstanceId SLACK = new SourceInstanceId("slack-a");
  protected static final SourceInstanceId OTHER_SLACK = new SourceInstanceId("slack-b");
  protected static final SourceInstanceId DRIVE = new SourceInstanceId("drive-a");
  protected static final SourceInstanceId NOTION = new SourceInstanceId("notion-a");
  protected static final SourceInstanceId JIRA = new SourceInstanceId("jira-a");
  protected static final Identity IDENTITY = new Identity(SLACK, "U1", false, false);
  protected static final Link LINK = new Link(SLACK, "U1", MEMBER_ID, "auto");
  protected IdentityStore store;

  protected abstract IdentityStore newStore();

  protected abstract void seed(AccessDirectory directory);

  @BeforeEach
  void createStore() {
    store = newStore();
  }

  @ParameterizedTest
  @ValueSource(strings = {"slack:invalid", "slack%invalid"})
  @DisplayName("비정규 인스턴스의 키만 제외하고 정상 인스턴스의 키를 유지한다(a11 m-3)")
  void malformedInstanceKeepsOtherKeys(String instanceId) {
    var instance = new SourceInstanceId(instanceId);
    seed(memberWithMalformedInstance(instance));

    var keys = store.accessKeysOf(MEMBER_ID, domainsWith(instance));

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactlyInAnyOrder(
            "user:slack-a:U1",
            "group:slack-a:C1",
            "scope:notion-a:*",
            "domain:drive-a:example.com");
  }

  @ParameterizedTest
  @ValueSource(strings = {"slack:invalid", "slack%invalid"})
  @DisplayName("비정규 인스턴스의 키를 제외하면 해당 인스턴스의 경고를 남긴다")
  void malformedInstanceWarns(String instanceId) {
    var instance = new SourceInstanceId(instanceId);
    seed(memberWithMalformedInstance(instance));
    try (var warnings = new IdentityWarnings(store)) {
      store.accessKeysOf(MEMBER_ID, domainsWith(instance));

      // then
      assertThat(warnings.messages()).contains("비정규 원천 인스턴스의 접근 키를 제외한다: " + instanceId);
    }
  }

  @Test
  @DisplayName("키가 없는 활성 사용자는 빈 키 집합으로 존재한다")
  void activeUserWithoutKeysIsPresent() {
    seed(activeMember());

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys).contains(Set.of());
  }

  @Test
  @DisplayName("등록되지 않은 사용자는 empty다")
  void unknownUserIsAbsent() {
    seed(activeMember().withScope(NOTION));

    var keys = store.accessKeysOf("unknown", NO_DOMAINS);

    // then
    assertThat(keys).isEmpty();
  }

  @Test
  @DisplayName("비활성 사용자는 연결·그룹·지정이 있어도 empty다")
  void inactiveUserIsAbsent() {
    seed(
        AccessDirectory.empty()
            .withUser(new User(MEMBER_ID, MEMBER.email(), false))
            .withIdentity(IDENTITY)
            .withLink(LINK)
            .withGroup(new Group(SLACK, "C1", Set.of("U1")))
            .withScope(NOTION));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys).isEmpty();
  }

  @Test
  @DisplayName("연결된 원천 신원마다 user 키를 갖는다")
  void linkedIdentitiesGiveUserKeys() {
    seed(
        linkedMember()
            .withIdentity(new Identity(OTHER_SLACK, "U1", false, false))
            .withLink(new Link(OTHER_SLACK, "U1", MEMBER_ID, "admin")));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactlyInAnyOrder("user:slack-a:U1", "user:slack-b:U1");
  }

  @Test
  @DisplayName("게스트 신원은 user·group 키를 주지 않는다")
  void guestIdentityDoesNotGiveKeys() {
    seed(
        activeMember()
            .withLink(LINK)
            .withIdentity(new Identity(SLACK, "U1", true, false))
            .withGroup(new Group(SLACK, "C1", Set.of("U1"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys).contains(Set.of());
  }

  @Test
  @DisplayName("원천이 관측하지 않은 연결 신원은 키를 주지 않는다")
  void unobservedIdentityDoesNotGiveKeys() {
    seed(activeMember().withLink(LINK).withGroup(new Group(SLACK, "C1", Set.of("U1"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys).contains(Set.of());
  }

  @Test
  @DisplayName("다른 인스턴스에서만 관측된 연결 신원은 키를 주지 않는다")
  void identityObservedOnlyInOtherInstanceDoesNotGiveKeys() {
    seed(
        activeMember()
            .withLink(LINK)
            .withIdentity(new Identity(OTHER_SLACK, "U1", false, false))
            .withGroup(new Group(OTHER_SLACK, "C1", Set.of("U1"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys).contains(Set.of());
  }

  @Test
  @DisplayName("연결이 없는 그룹 구성원 신원은 키를 주지 않는다")
  void unlinkedIdentityDoesNotGiveKeys() {
    seed(activeMember().withIdentity(IDENTITY).withGroup(new Group(SLACK, "C1", Set.of("U1"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys).contains(Set.of());
  }

  @Test
  @DisplayName("탈퇴 신원은 admin 연결이어도 user·group 키를 주지 않는다")
  void deletedAdminLinkedIdentityDoesNotGiveKeys() {
    seed(
        activeMember()
            .withLink(new Link(SLACK, "U1", MEMBER_ID, "admin"))
            .withIdentity(new Identity(SLACK, "U1", false, true))
            .withGroup(new Group(SLACK, "C1", Set.of("U1"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys).contains(Set.of());
  }

  @Test
  @DisplayName("구성원인 그룹마다 group 키를 갖는다")
  void membershipGivesGroupKeys() {
    seed(
        linkedMember()
            .withGroup(new Group(SLACK, "C1", Set.of("U1")))
            .withGroup(new Group(SLACK, "C2", Set.of("U1", "U2"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactlyInAnyOrder("user:slack-a:U1", "group:slack-a:C1", "group:slack-a:C2");
  }

  @Test
  @DisplayName("다른 인스턴스의 같은 신원 id는 그 인스턴스 그룹에 들지 않는다")
  void sameIdentityInOtherInstanceDoesNotJoinGroup() {
    seed(linkedMember().withGroup(new Group(OTHER_SLACK, "C1", Set.of("U1"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactly("user:slack-a:U1");
  }

  @Test
  @DisplayName("합성 그룹의 구성원은 그 합성 그룹을 참조하는 그룹의 키를 받는다")
  void syntheticMembershipGivesReferringGroupKey() {
    seed(
        linkedMember()
            .withGroup(new Group(SLACK, "~members", Set.of("U1")))
            .withGroup(new Group(SLACK, "C1", Set.of("~members"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactlyInAnyOrder("user:slack-a:U1", "group:slack-a:~members", "group:slack-a:C1");
  }

  @Test
  @DisplayName("합성 그룹 참조는 한 단계만 펼친다")
  void syntheticReferencesExpandOnlyOneLevel() {
    seed(
        linkedMember()
            .withGroup(new Group(SLACK, "~b", Set.of("U1")))
            .withGroup(new Group(SLACK, "~a", Set.of("~b")))
            .withGroup(new Group(SLACK, "C1", Set.of("~a"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactlyInAnyOrder("user:slack-a:U1", "group:slack-a:~b");
  }

  @Test
  @DisplayName("다른 인스턴스의 합성 그룹은 참조로 펼치지 않는다")
  void otherInstanceSyntheticGroupDoesNotExpand() {
    seed(
        linkedMember()
            .withGroup(new Group(SLACK, "~members", Set.of("U1")))
            .withGroup(new Group(OTHER_SLACK, "C1", Set.of("~members"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactlyInAnyOrder("user:slack-a:U1", "group:slack-a:~members");
  }

  @Test
  @DisplayName("예약 접두 없는 실제 그룹 id는 참조로 펼치지 않는다")
  void ordinaryGroupIsNotAReference() {
    seed(
        linkedMember()
            .withGroup(new Group(SLACK, "G1", Set.of("U1")))
            .withGroup(new Group(SLACK, "C1", Set.of("G1"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactlyInAnyOrder("user:slack-a:U1", "group:slack-a:G1");
  }

  @Test
  @DisplayName("콜론이 든 그룹 id는 AccessKey 정규형으로 나온다")
  void colonInGroupIdUsesCanonicalKey() {
    seed(
        activeMember()
            .withIdentity(new Identity(JIRA, "U1", false, false))
            .withLink(new Link(JIRA, "U1", MEMBER_ID, "auto"))
            .withGroup(new Group(JIRA, "~project:10001", Set.of("U1"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactlyInAnyOrder("user:jira-a:U1", "group:jira-a:~project%3A10001");
  }

  @Test
  @DisplayName("연결 없는 활성 사용자는 지정된 소스의 scope 키만 갖는다")
  void unlinkedUserGetsDesignatedScopeOnly() {
    seed(activeMember().withScope(NOTION));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactly("scope:notion-a:*");
  }

  @Test
  @DisplayName("허용 도메인·인스턴스 도메인 규칙을 만족하면 원천 신원 없이 domain 키를 받는다")
  void domainKeysDoNotRequireSourceIdentity() {
    seed(activeMember());
    var domains = new DomainPolicy(Set.of("example.com"), Map.of(DRIVE, "example.com"));

    var keys = store.accessKeysOf(MEMBER_ID, domains);

    // then
    assertThat(keys.orElseThrow())
        .extracting(AccessKey::toString)
        .containsExactly("domain:drive-a:example.com");
  }

  @Test
  @DisplayName("인스턴스 도메인이 조직 허용 목록에 없으면 domain 키를 주지 않는다")
  void unlistedDomainDoesNotGiveDomainKey() {
    seed(activeMember());
    var domains = new DomainPolicy(Set.of("other.example"), Map.of(DRIVE, "example.com"));

    var keys = store.accessKeysOf(MEMBER_ID, domains);

    // then
    assertThat(keys).contains(Set.of());
  }

  @Test
  @DisplayName("허용 목록 밖 이메일 도메인의 사용자는 domain 키를 받지 않는다")
  void unlistedEmailDomainDoesNotGiveDomainKey() {
    seed(AccessDirectory.empty().withUser(new User(MEMBER_ID, "member@other.example", true)));
    var domains = new DomainPolicy(Set.of("example.com"), Map.of(DRIVE, "example.com"));

    var keys = store.accessKeysOf(MEMBER_ID, domains);

    // then
    assertThat(keys).contains(Set.of());
  }

  @Test
  @DisplayName("인스턴스 도메인이 다르면 domain 키를 받지 않는다")
  void differentInstanceDomainDoesNotGiveKey() {
    seed(activeMember());
    var domains = new DomainPolicy(Set.of("example.com"), Map.of(DRIVE, "other.example"));

    var keys = store.accessKeysOf(MEMBER_ID, domains);

    // then
    assertThat(keys).contains(Set.of());
  }

  @Test
  @DisplayName("다른 사용자에게 연결된 신원은 내 키를 주지 않는다")
  void otherUsersIdentityDoesNotGiveKeys() {
    seed(
        activeMember()
            .withUser(new User("other", "other@example.com", true))
            .withIdentity(IDENTITY)
            .withLink(new Link(SLACK, "U1", "other", "auto"))
            .withGroup(new Group(SLACK, "C1", Set.of("U1"))));

    var keys = store.accessKeysOf(MEMBER_ID, NO_DOMAINS);

    // then
    assertThat(keys).contains(Set.of());
  }

  private AccessDirectory activeMember() {
    return AccessDirectory.empty().withUser(MEMBER);
  }

  private AccessDirectory memberWithMalformedInstance(SourceInstanceId instance) {
    return linkedMember()
        .withGroup(new Group(SLACK, "C1", Set.of("U1")))
        .withScope(NOTION)
        .withIdentity(new Identity(instance, "U1", false, false))
        .withLink(new Link(instance, "U1", MEMBER_ID, "auto"))
        .withGroup(new Group(instance, "~members", Set.of("U1")))
        .withGroup(new Group(instance, "C1", Set.of("~members")))
        .withScope(instance);
  }

  private DomainPolicy domainsWith(SourceInstanceId instance) {
    return new DomainPolicy(
        Set.of("example.com"), Map.of(DRIVE, "example.com", instance, "example.com"));
  }

  private AccessDirectory linkedMember() {
    return activeMember().withIdentity(IDENTITY).withLink(LINK);
  }
}

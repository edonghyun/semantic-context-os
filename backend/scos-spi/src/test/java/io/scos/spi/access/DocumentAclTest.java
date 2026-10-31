package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("문서 ACL은 관측 상태와 키로 접근을 판정한다")
class DocumentAclTest {
  private static final Instant OBSERVED = Instant.parse("2026-10-26T00:00:00Z");
  private static final AccessKey USER = AccessKey.parse("user:slack-a:U1");

  @Test
  @DisplayName("문서 id가 null이면 생성할 수 없다")
  void rejectsNullDocumentId() {
    // when·then
    assertThatThrownBy(() -> new DocumentAcl(null, DocumentAcl.Status.OK, Set.of(USER), OBSERVED))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("documentId");
  }

  @Test
  @DisplayName("상태가 null이면 생성할 수 없다")
  void rejectsNullStatus() {
    // when·then
    assertThatThrownBy(() -> new DocumentAcl("doc", null, Set.of(USER), OBSERVED))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("status");
  }

  @Test
  @DisplayName("관측 시각이 null이면 생성할 수 없다")
  void rejectsNullObservation() {
    // when·then
    assertThatThrownBy(() -> new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("observedAt");
  }

  @Test
  @DisplayName("키 집합이 null이면 생성할 수 없다")
  void rejectsNullKeys() {
    // when·then
    assertThatThrownBy(() -> new DocumentAcl("doc", DocumentAcl.Status.OK, null, OBSERVED))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("OK 문서는 키가 겹치는 요청자에게 보인다")
  void matchingOkIsVisible() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);

    boolean visible = acl.visibleTo(Set.of(USER));

    // then
    assertThat(visible).isTrue();
  }

  @Test
  @DisplayName("UNKNOWN 문서는 키가 겹쳐도 보이지 않는다")
  void unknownIsHidden() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.UNKNOWN, Set.of(USER), OBSERVED);

    boolean visible = acl.visibleTo(Set.of(USER));

    // then
    assertThat(visible).isFalse();
  }

  @Test
  @DisplayName("UNSUPPORTED 문서는 scope 키가 겹치면 보인다")
  void unsupportedScopeIsVisible() {
    var scope = AccessKey.parse("scope:slack-a:*");
    var acl = new DocumentAcl("doc", DocumentAcl.Status.UNSUPPORTED, Set.of(scope), OBSERVED);

    boolean visible = acl.visibleTo(Set.of(scope));

    // then
    assertThat(visible).isTrue();
  }

  @Test
  @DisplayName("UNSUPPORTED에 scope 아닌 키를 넣으면 거부한다")
  void unsupportedRejectsUserKey() {
    // then
    assertThatThrownBy(
            () -> new DocumentAcl("doc", DocumentAcl.Status.UNSUPPORTED, Set.of(USER), OBSERVED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("ACL은 입력 키 집합을 방어 복사한다")
  void keysAreCopied() {
    var keys = new HashSet<>(Set.of(USER));
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, keys, OBSERVED);

    keys.clear();

    // then
    assertThat(acl.keys()).containsExactly(USER);
  }

  @Test
  @DisplayName("fingerprint는 sha256 소문자 16진수다")
  void fingerprintIsSha256() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);

    String fingerprint = acl.fingerprint();

    // then
    assertThat(fingerprint).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("FAILED 문서는 키가 겹쳐도 보이지 않는다")
  void failedIsHidden() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(USER), OBSERVED);

    boolean visible = acl.visibleTo(Set.of(USER));

    // then
    assertThat(visible).isFalse();
  }

  @Test
  @DisplayName("키가 겹치지 않으면 보이지 않는다")
  void differentKeyIsHidden() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);

    boolean visible = acl.visibleTo(Set.of(AccessKey.parse("user:slack-a:U2")));

    // then
    assertThat(visible).isFalse();
  }

  @Test
  @DisplayName("빈 요청자 키에는 문서가 보이지 않는다")
  void emptyRequesterIsHidden() {
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);

    boolean visible = acl.visibleTo(Set.of());

    // then
    assertThat(visible).isFalse();
  }

  @Test
  @DisplayName("fingerprint는 키 순서와 무관하다")
  void fingerprintIgnoresKeyOrder() {
    var group = AccessKey.parse("group:slack-a:C1");
    var left =
        new DocumentAcl(
            "doc", DocumentAcl.Status.OK, new LinkedHashSet<>(List.of(USER, group)), OBSERVED);
    var right =
        new DocumentAcl(
            "doc", DocumentAcl.Status.OK, new LinkedHashSet<>(List.of(group, USER)), OBSERVED);

    // then
    assertThat(left.fingerprint()).isEqualTo(right.fingerprint());
  }

  @Test
  @DisplayName("fingerprint는 상태가 다르면 다르다")
  void fingerprintIncludesStatus() {
    var ok = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    var failed = new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(USER), OBSERVED);

    // then
    assertThat(ok.fingerprint()).isNotEqualTo(failed.fingerprint());
  }

  @Test
  @DisplayName("fingerprint는 키가 다르면 다르다")
  void fingerprintIncludesKeys() {
    var first = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    var second = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(), OBSERVED);

    // then
    assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
  }

  @Test
  @DisplayName("fingerprint는 관측 시각과 문서 id를 포함하지 않는다")
  void fingerprintIgnoresObservation() {
    var first = new DocumentAcl("first", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);
    var second =
        new DocumentAcl("second", DocumentAcl.Status.OK, Set.of(USER), OBSERVED.plusSeconds(1));

    // then
    assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
  }

  @Test
  @DisplayName("Unrestricted는 ACL 행이 없는 문서도 받아들인다")
  void unrestrictedAdmitsMissingAcl() {
    var access = new DocumentAccess(AccessScope.singleUser());

    boolean admitted = access.admits(Optional.empty());

    // then
    assertThat(admitted).isTrue();
  }

  @Test
  @DisplayName("Restricted는 키가 겹치는 ACL 문서를 받아들인다")
  void restrictedAdmitsMatchingAcl() {
    var access =
        new DocumentAccess(new AccessScope.Restricted(Principal.singleUser(), Set.of(USER)));
    var acl = new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED);

    boolean admitted = access.admits(Optional.of(acl));

    // then
    assertThat(admitted).isTrue();
  }

  @Test
  @DisplayName("Restricted는 ACL 행이 없는 문서를 받아들이지 않는다")
  void restrictedRejectsMissingAcl() {
    var access =
        new DocumentAccess(new AccessScope.Restricted(Principal.singleUser(), Set.of(USER)));

    boolean admitted = access.admits(Optional.empty());

    // then
    assertThat(admitted).isFalse();
  }

  @Test
  @DisplayName("빈 Restricted 키는 ACL 문서를 받아들이지 않는다")
  void restrictedRejectsEmptyKeys() {
    var access = new DocumentAccess(new AccessScope.Restricted(Principal.singleUser(), Set.of()));

    boolean admitted =
        access.admits(
            Optional.of(new DocumentAcl("doc", DocumentAcl.Status.OK, Set.of(USER), OBSERVED)));

    // then
    assertThat(admitted).isFalse();
  }

  @Test
  @DisplayName("Restricted는 FAILED ACL을 받아들이지 않는다")
  void restrictedRejectsFailedAcl() {
    var access =
        new DocumentAccess(new AccessScope.Restricted(Principal.singleUser(), Set.of(USER)));

    boolean admitted =
        access.admits(
            Optional.of(new DocumentAcl("doc", DocumentAcl.Status.FAILED, Set.of(USER), OBSERVED)));

    // then
    assertThat(admitted).isFalse();
  }
}

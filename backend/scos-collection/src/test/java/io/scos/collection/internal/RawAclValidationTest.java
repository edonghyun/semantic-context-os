package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.SourceDescriptor;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("수집 ACL 검증은 원천 선언과 키의 의미를 함께 검사한다")
class RawAclValidationTest {
  final RawAclValidation validation = new RawAclValidation();
  final SourceDescriptor source =
      SourceInstances.noop(AclCommitScenario.SOURCE, AclCommitScenario.T0).descriptor();
  final SourceDescriptor notion =
      SourceInstances.of(
              AclCommitScenario.SOURCE, "notion", "{}", "env:NOTION_TOKEN", AclCommitScenario.T0)
          .descriptor();

  @Test
  @DisplayName("비관측을 그대로 보존한다")
  void notObservedIsPreserved() {
    var result =
        validation.validate(
            source, AccessPolicy.supported(Set.of(), Set.of()), RawAcl.notObserved());
    // then
    assertThat(result).isEqualTo(new RawAcl.NotObserved());
  }

  @Test
  @DisplayName("지원 원천의 사용자 키를 허용한다")
  void supportedUserIsPreserved() {
    var result =
        validation.validate(
            source,
            AccessPolicy.supported(Set.of(), Set.of()),
            acl(Status.OK, "user:acl-source:U1"));
    // then
    assertThat(result)
        .isEqualTo(
            new RawAcl.Observed(
                Status.OK, Set.of(AccessKey.parse("user:acl-source:U1")), AclCommitScenario.T1));
  }

  @Test
  @DisplayName("지원 원천의 그룹 키를 허용한다")
  void supportedGroupIsPreserved() {
    var result =
        validation.validate(
            source,
            AccessPolicy.supported(Set.of(), Set.of()),
            acl(Status.OK, "group:acl-source:~G1"));
    // then
    assertThat(result)
        .isEqualTo(
            new RawAcl.Observed(
                Status.OK, Set.of(AccessKey.parse("group:acl-source:~G1")), AclCommitScenario.T1));
  }

  @Test
  @DisplayName("지원 원천의 도메인 키를 허용한다")
  void supportedDomainIsPreserved() {
    var result =
        validation.validate(
            source,
            AccessPolicy.supported(Set.of(), Set.of()),
            acl(Status.OK, "domain:acl-source:example.com"));
    // then
    assertThat(result)
        .isEqualTo(
            new RawAcl.Observed(
                Status.OK,
                Set.of(AccessKey.parse("domain:acl-source:example.com")),
                AclCommitScenario.T1));
  }

  @Test
  @DisplayName("외부 인스턴스 키를 차단한다")
  void wrongInstanceIsFailed() {
    var result =
        validation.validate(
            source, AccessPolicy.supported(Set.of(), Set.of()), acl(Status.OK, "user:other:U1"));
    // then
    assertThat(result).isEqualTo(invalidCarrier());
  }

  @Test
  @DisplayName("지원 원천 scope 키를 차단한다")
  void supportedScopeIsFailed() {
    var result =
        validation.validate(
            source,
            AccessPolicy.supported(Set.of(), Set.of()),
            acl(Status.OK, "scope:acl-source:*"));
    // then
    assertThat(result).isEqualTo(invalidCarrier());
  }

  @Test
  @DisplayName("지원 원천의 UNSUPPORTED를 차단한다")
  void supportedUnsupportedStatusIsFailed() {
    var result =
        validation.validate(
            source,
            AccessPolicy.supported(Set.of(), Set.of()),
            acl(Status.UNSUPPORTED, "scope:acl-source:*"));
    // then
    assertThat(result).isEqualTo(invalidCarrier());
  }

  @Test
  @DisplayName("Notion 미지원 선언의 scope만 허용한다")
  void notionScopeIsPreserved() {
    var result =
        validation.validate(
            notion, AccessPolicy.scoped("notion"), acl(Status.UNSUPPORTED, "scope:acl-source:*"));
    // then
    assertThat(result)
        .isEqualTo(
            new RawAcl.Observed(
                Status.UNSUPPORTED,
                Set.of(AccessKey.parse("scope:acl-source:*")),
                AclCommitScenario.T1));
  }

  @Test
  @DisplayName("다른 미지원 원천의 scope를 차단한다")
  void nonNotionUnsupportedIsFailed() {
    var result =
        validation.validate(
            source, AccessPolicy.unsupported(), acl(Status.UNSUPPORTED, "scope:acl-source:*"));
    // then
    assertThat(result).isEqualTo(invalidCarrier());
  }

  @Test
  @DisplayName("미지원 ACL의 사용자 키를 차단한다")
  void unsupportedUserIsFailed() {
    var result =
        validation.validate(
            notion, AccessPolicy.scoped("notion"), acl(Status.UNSUPPORTED, "user:acl-source:U1"));
    // then
    assertThat(result).isEqualTo(invalidCarrier());
  }

  @Test
  @DisplayName("scope 없는 UNSUPPORTED를 차단한다")
  void unsupportedWithoutScopeIsFailed() {
    var result =
        validation.validate(
            notion,
            AccessPolicy.scoped("notion"),
            RawAcl.observed(Status.UNSUPPORTED, Set.of(), AclCommitScenario.T1));
    // then
    assertThat(result).isEqualTo(invalidCarrier());
  }

  @Test
  @DisplayName("미지원 원천의 OK를 차단한다")
  void unsupportedOkIsFailed() {
    var result =
        validation.validate(
            notion, AccessPolicy.scoped("notion"), acl(Status.OK, "user:acl-source:U1"));
    // then
    assertThat(result).isEqualTo(invalidCarrier());
  }

  @Test
  @DisplayName("UNKNOWN의 키를 비운다")
  void unknownGrantsNobody() {
    var result =
        validation.validate(
            source,
            AccessPolicy.supported(Set.of(), Set.of()),
            acl(Status.UNKNOWN, "user:acl-source:U1"));
    // then
    assertThat(result)
        .isEqualTo(new RawAcl.Observed(Status.UNKNOWN, Set.of(), AclCommitScenario.T1));
  }

  @Test
  @DisplayName("FAILED의 키를 비운다")
  void failedGrantsNobody() {
    var result =
        validation.validate(
            source,
            AccessPolicy.supported(Set.of(), Set.of()),
            acl(Status.FAILED, "user:acl-source:U1"));
    // then
    assertThat(result)
        .isEqualTo(
            new RawAcl.Observed(
                Status.FAILED,
                Set.of(),
                AclCommitScenario.T1,
                AccessFailureReason.PROVIDER_FAILURE));
  }

  @Test
  @DisplayName("Notion의 OK scope도 차단한다")
  void notionOkScopeIsFailed() {
    var result =
        validation.validate(
            notion, AccessPolicy.scoped("notion"), acl(Status.OK, "scope:acl-source:*"));
    // then
    assertThat(result).isEqualTo(invalidCarrier());
  }

  @Test
  @DisplayName("Notion도 지원을 선언했으면 UNSUPPORTED 관측을 거부한다")
  void supportedNotionRejectsUnsupportedStatus() {
    var result =
        validation.validate(
            notion,
            AccessPolicy.supported(Set.of(), Set.of()),
            acl(Status.UNSUPPORTED, "scope:acl-source:*"));
    // then
    assertThat(result).isEqualTo(invalidCarrier());
  }

  private RawAcl acl(Status status, String key) {
    return RawAcl.observed(status, Set.of(AccessKey.parse(key)), AclCommitScenario.T1);
  }

  private RawAcl.Observed invalidCarrier() {
    return new RawAcl.Observed(
        Status.FAILED, Set.of(), AclCommitScenario.T1, AccessFailureReason.INVALID_CARRIER);
  }
}

package io.scos.connector.tiro.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.connector.tiro.internal.TiroNoteAccess.Observation;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** 노트 하나의 접근 키는 협업자 이메일(user)과, 전원 공개면 조직 도메인(domain)이다(Tiro 접근 설계 §5.1·§5.4). 참석자는 권한이 아니다. */
@DisplayName("Tiro 노트 키는 협업자 이메일과 공개 노트의 조직 도메인이다")
class TiroNoteAccessTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SourceInstanceId SOURCE = new SourceInstanceId("tiro-a");
  private static final Instant AT = Instant.parse("2026-11-24T00:00:00Z");
  private static final DomainPolicy DOMAINS =
      new DomainPolicy(Set.of("example.com"), Map.of(SOURCE, "example.com"));

  private static Observation observe(DomainPolicy domains, String stored, boolean everyone) {
    return new TiroNoteAccess(SOURCE, domains, JSON).observe(stored, everyone, AT);
  }

  private static Observation observe(String stored, boolean everyone) {
    return observe(DOMAINS, stored, everyone);
  }

  /** 저장 원본(`tiro.note`): note 안의 collaborators 값(JSON 조각). */
  private static String stored(String collaborators) {
    return "{\"note\":{\"guid\":\"n1\",\"collaborators\":"
        + collaborators
        + "},\"paragraphs\":[],\"summaries\":[],\"documents\":[],\"unavailableResources\":[]}";
  }

  private static String collaborator(String email) {
    return "{\"email\":\"" + email + "\",\"role\":\"OWNER\"}";
  }

  private static Set<String> keys(Observation observation) {
    Set<String> out = new TreeSet<>();
    observation.acl().keys().forEach(k -> out.add(k.toString()));
    return out;
  }

  @Test
  @DisplayName("협업자 이메일은 소문자 user 키이고 상태는 OK다")
  void collaboratorBecomesUserKey() {
    // when
    Observation observation = observe(stored("[" + collaborator("Owner@Example.com") + "]"), false);

    // then
    assertThat(keys(observation)).containsExactly("user:tiro-a:owner@example.com");
    assertThat(observation.acl().status()).isEqualTo(DocumentAcl.Status.OK);
  }

  @Test
  @DisplayName("전원 공개 노트는 조직 도메인 키를 더한다")
  void everyoneAddsOrgDomainKey() {
    // when
    Observation observation = observe(stored("[" + collaborator("owner@example.com") + "]"), true);

    // then
    assertThat(keys(observation))
        .containsExactly("domain:tiro-a:example.com", "user:tiro-a:owner@example.com");
  }

  @Test
  @DisplayName("인스턴스 도메인 설정이 없어도 도메인 키를 낸다")
  void domainKeyDoesNotNeedInstanceDomain() {
    // given
    DomainPolicy withoutInstance = new DomainPolicy(Set.of("example.com"), Map.of());

    // when
    Observation observation = observe(withoutInstance, stored("[]"), true);

    // then
    assertThat(keys(observation)).containsExactly("domain:tiro-a:example.com");
  }

  @Test
  @DisplayName("쓸 수 있는 협업자 이메일이 없으면 키 없는 OK다")
  void noUsableCollaboratorIsOkWithNoKeys() {
    // when
    Observation observation =
        observe(
            stored(
                "[{\"role\":\"OWNER\"},"
                    + collaborator("no-at")
                    + ","
                    + collaborator("@example.com")
                    + ","
                    + collaborator("a@")
                    + ","
                    + collaborator("a@b@example.com")
                    + ",{\"email\":7},\"x\"]"),
            false);

    // then
    assertThat(observation.acl().status()).isEqualTo(DocumentAcl.Status.OK);
    assertThat(observation.acl().keys()).isEmpty();
  }

  @ParameterizedTest(name = "원본 {0}")
  @ValueSource(
      strings = {
        "{\"note\":{\"guid\":\"n1\"}}",
        "{\"note\":{\"guid\":\"n1\",\"collaborators\":\"x\"}}"
      })
  @DisplayName("collaborators가 없거나 배열이 아니면 키 없는 OK다")
  void missingCollaboratorsYieldsNoUserKeys(String stored) {
    // when
    Observation observation = observe(stored, false);

    // then
    assertThat(observation.acl().status()).isEqualTo(DocumentAcl.Status.OK);
    assertThat(observation.acl().keys()).isEmpty();
  }

  @Test
  @DisplayName("외부 도메인 협업자도 user 키이고 신원은 미검증이다")
  void externalCollaboratorKeepsKey() {
    // when
    Observation observation = observe(stored("[" + collaborator("a@gmail.com") + "]"), false);

    // then
    assertThat(keys(observation)).containsExactly("user:tiro-a:a@gmail.com");
    assertThat(observation.identities())
        .extracting(SourceIdentityRecord::emailVerified)
        .containsExactly(false);
  }

  @Test
  @DisplayName("조직 도메인 협업자의 신원은 검증된 이메일이다")
  void orgCollaboratorIdentityIsVerified() {
    // when
    Observation observation = observe(stored("[" + collaborator("b@example.com") + "]"), false);

    // then
    assertThat(observation.identities())
        .containsExactly(
            new SourceIdentityRecord(
                "b@example.com", Optional.of("b@example.com"), true, false, false, AT));
  }

  @Test
  @DisplayName("대소문자만 다른 같은 이메일은 키 하나·신원 하나다")
  void duplicateEmailsYieldOneIdentity() {
    // when
    Observation observation =
        observe(
            stored("[" + collaborator("B@example.com") + "," + collaborator("b@EXAMPLE.com") + "]"),
            false);

    // then
    assertThat(keys(observation)).containsExactly("user:tiro-a:b@example.com");
    assertThat(observation.identities()).hasSize(1);
  }

  @Test
  @DisplayName("참석자 이메일은 키가 되지 않는다")
  void participantsAreIgnored() {
    // when
    Observation observation =
        observe(
            "{\"note\":{\"guid\":\"n1\",\"collaborators\":["
                + collaborator("owner@example.com")
                + "],\"participants\":[{\"name\":\"p\",\"email\":\"p@example.com\"}]}}",
            false);

    // then
    assertThat(keys(observation)).containsExactly("user:tiro-a:owner@example.com");
  }

  @ParameterizedTest(name = "원본 {0}")
  @ValueSource(strings = {"not json", "[]", "{\"note\":[]}", "{}"})
  @DisplayName("JSON이 아니거나 note가 객체가 아닌 원본은 프로토콜 위반이다")
  void malformedStoredContentIsProtocolViolation(String stored) {
    // when·then
    assertThatThrownBy(() -> observe(stored, false)).isInstanceOf(TiroProtocolException.class);
  }
}

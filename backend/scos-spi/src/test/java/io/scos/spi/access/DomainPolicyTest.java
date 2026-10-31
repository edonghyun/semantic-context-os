package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.connector.SourceInstanceId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("조직 도메인은 허용 목록과 인스턴스 도메인이 일치해야 허용된다")
class DomainPolicyTest {
  private static final SourceInstanceId DRIVE = new SourceInstanceId("drive-a");

  @Test
  @DisplayName("허용된 조직과 인스턴스 도메인이 일치하면 허용한다")
  void matchingDomainIsAdmitted() {
    var policy = new DomainPolicy(Set.of("example.com"), Map.of(DRIVE, "example.com"));

    boolean admitted = policy.admits("member@example.com", DRIVE);

    // then
    assertThat(admitted).isTrue();
  }

  @Test
  @DisplayName("설정 도메인과 이메일은 대소문자를 무시한다")
  void domainsIgnoreCase() {
    var policy = new DomainPolicy(Set.of("EXAMPLE.COM"), Map.of(DRIVE, "Example.Com"));

    boolean admitted = policy.admits("Member@EXAMPLE.com", DRIVE);

    // then
    assertThat(admitted).isTrue();
  }

  @Test
  @DisplayName("@ 없는 이메일은 거부한다")
  void missingSeparatorIsDenied() {
    var policy = new DomainPolicy(Set.of("example.com"), Map.of(DRIVE, "example.com"));

    boolean admitted = policy.admits("example.com", DRIVE);

    // then
    assertThat(admitted).isFalse();
  }

  @Test
  @DisplayName("조직 도메인이 없는 인스턴스는 거부한다")
  void unknownInstanceIsDenied() {
    var policy = new DomainPolicy(Set.of("example.com"), Map.of());

    boolean admitted = policy.admits("member@example.com", DRIVE);

    // then
    assertThat(admitted).isFalse();
  }

  @Test
  @DisplayName("조직 허용 목록 밖 도메인은 거부한다")
  void unlistedDomainIsDenied() {
    var policy = new DomainPolicy(Set.of("other.example"), Map.of(DRIVE, "example.com"));

    boolean admitted = policy.admits("member@example.com", DRIVE);

    // then
    assertThat(admitted).isFalse();
  }

  @Test
  @DisplayName("인스턴스 조직 도메인이 다르면 거부한다")
  void differentInstanceDomainIsDenied() {
    var policy = new DomainPolicy(Set.of("example.com"), Map.of(DRIVE, "other.example"));

    boolean admitted = policy.admits("member@example.com", DRIVE);

    // then
    assertThat(admitted).isFalse();
  }
}

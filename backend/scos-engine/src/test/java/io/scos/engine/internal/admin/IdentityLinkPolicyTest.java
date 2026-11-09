package io.scos.engine.internal.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.admin.AutomaticIdentityLinksContract.IdentityBuilder;
import io.scos.spi.access.AdminRecords.ObservedIdentity;
import io.scos.spi.access.AdminRecords.Role;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.access.AdminRecords.UserAccount;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.memory.MemoryAdminScenario;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("조직 신원 정책은 정규 이메일과 활성 계정을 일치시킨다(§7.3)")
class IdentityLinkPolicyTest {
  private final PolicyScenario scenario = new PolicyScenario();

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "alice", "@example.com", "alice@", "alice@@example.com"})
  @DisplayName("이메일의 필수 부분이 없거나 구분자가 중복되면 일치시키지 않는다")
  void malformedEmailDoesNotMatch(String email) {
    // when
    var result = scenario.match(new IdentityBuilder().email(email).build());
    // then
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("로컬부 없는 과거 계정이 있어도 잘못된 외부 이메일은 연결하지 않는다")
  void malformedIdentityCannotMatchLegacyAccount() {
    scenario.givenLegacyAccountWithoutLocalPart();
    // when
    var result = scenario.match(new IdentityBuilder().email("@example.com").build());
    // then
    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("조직 허용 도메인은 이메일 양끝 공백과 대소문자를 정규화한다")
  void normalizedEmailMatchesActiveUser() {
    // when
    var result = scenario.match(new IdentityBuilder().email("  ALICE@EXAMPLE.COM  ").build());
    // then
    assertThat(result).map(UserAccount::id).contains("alice");
  }

  @Test
  @DisplayName("조직 허용 목록이 비어 있으면 일치하는 신원도 연결하지 않는다")
  void emptyOrganizationDoesNotMatch() {
    // given
    scenario.noOrganizationDomains();
    // when
    var result = scenario.match(new IdentityBuilder().build());
    // then
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"slack", "google-drive", "jira", "notion", "unknown"})
  @DisplayName("Tiro 이외 원천은 검증 플래그가 없으면 연결하지 않는다")
  void onlyTiroMaySkipVerification(String kind) {
    // given
    scenario.kind(kind);
    // when
    var result = scenario.match(new IdentityBuilder().unverified().build());
    // then
    assertThat(result).isEmpty();
  }

  private static final class PolicyScenario {
    private static final SourceInstanceId SOURCE = new SourceInstanceId("slack-a");
    private final MemoryAdminScenario storage = new MemoryAdminScenario();
    private DomainPolicy domains =
        new DomainPolicy(Set.of("EXAMPLE.COM"), Map.of(SOURCE, "other.example"));
    private String kind = "slack";

    private PolicyScenario() {
      storage
          .store()
          .transact(
              session -> {
                session.insertUser(
                    new UserAccount(
                        "alice",
                        "alice@example.com",
                        true,
                        Role.MEMBER,
                        Instant.parse("2026-11-08T00:00:00Z")));
                return true;
              });
    }

    void noOrganizationDomains() {
      domains = new DomainPolicy(Set.of(), Map.of());
    }

    void givenLegacyAccountWithoutLocalPart() {
      storage
          .store()
          .transact(
              session -> {
                session.insertUser(
                    new UserAccount(
                        "legacy",
                        "@example.com",
                        true,
                        Role.MEMBER,
                        Instant.parse("2026-11-08T00:00:00Z")));
                return true;
              });
    }

    void kind(String value) {
      kind = value;
    }

    Optional<UserAccount> match(SourceIdentityRecord identity) {
      return storage
          .store()
          .transact(
              session ->
                  new IdentityLinkPolicy(domains)
                      .matchingUser(
                          new SourceAccount(SOURCE, kind),
                          new ObservedIdentity(SOURCE, identity),
                          session));
    }
  }
}

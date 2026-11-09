package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.store.Principal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("db")
@DisplayName("V34는 기존 인증과 관리 행을 보존하며 토큰 식별자를 추가한다")
class AdminMigrationTest {
  @Test
  @DisplayName("기존 토큰마다 서로 다른 필수 UUID를 채운다")
  void existingTokensReceiveDistinctMetadataIds() {
    var scenario = new AdminMigrationScenario("scos_admin_migration_ids");

    scenario.migrate();

    // then
    assertThat(scenario.tokenIds()).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("기존 활성 토큰만 인증되고 폐기 토큰은 계속 거부된다")
  void migrationPreservesAuthenticationAndRevocation() {
    var scenario = new AdminMigrationScenario("scos_admin_migration_auth");

    scenario.migrate();

    // then
    assertThat(scenario.authentication())
        .containsExactly(Optional.of(new Principal("alice")), Optional.empty());
  }

  @Test
  @DisplayName("V33 사용자와 연결과 지정과 감사의 값을 보존한다")
  void v33DataSurvivesAdminMigration() {
    var scenario = new AdminMigrationScenario("scos_admin_migration_data");

    scenario.migrate();

    // then
    assertThat(scenario.retainedData())
        .containsExactly(
            "audit:1:alice:user.create:user:bob:{\"role\": \"member\"}:1793491200",
            "link:slack-a:U01:alice:admin:alice:1793491200",
            "scope:notion-a:alice:1793491200",
            "user:alice:alice@example.com:true:admin:1793491200",
            "user:bob:bob@example.com:true:member:1793491200");
  }

  @Test
  @DisplayName("기존 저장 포트로 추가한 토큰도 기본 UUID를 받는다")
  void newTokensReceiveMetadataIdByDefault() {
    var scenario = new AdminMigrationScenario("scos_admin_migration_default");
    scenario.migrate();

    scenario.storeNewToken();

    // then
    assertThat(scenario.tokenIds()).hasSize(3).doesNotContainNull().doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("같은 메타데이터 식별자의 저장은 거부한다")
  void metadataIdMustBeUnique() {
    var scenario = new AdminMigrationScenario("scos_admin_migration_unique");
    scenario.migrate();

    // when·then
    assertThatThrownBy(scenario::storeDuplicateMetadataId)
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("메타데이터 식별자의 NULL은 거부한다")
  void metadataIdMustBePresent() {
    var scenario = new AdminMigrationScenario("scos_admin_migration_not_null");
    scenario.migrate();

    // when·then
    assertThatThrownBy(scenario::storeNullMetadataId)
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("감사 시각과 id를 내림차순으로 탐색하는 인덱스를 추가한다")
  void auditHasDescendingKeysetIndex() {
    var scenario = new AdminMigrationScenario("scos_admin_migration_index");

    scenario.migrate();

    // then
    assertThat(scenario.auditKeysetIndex()).contains("(at DESC, id DESC)");
  }
}

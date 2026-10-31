package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("접근 코어 마이그레이션은 기존 데이터에서 접근 표를 만든다")
class AccessCoreMigrationTest {
  static final String V_CORE = "23";

  private static final String V_RECONCILIATION = "22";

  @Test
  @DisplayName("접근 코어의 직전 상태는 재조정 V22까지 적용되어 있다")
  void startsAfterReconciliationMigrations() {
    var scenario = new AccessCoreMigrationScenario("scos_access_after_reconciliation");

    var before = scenario.snapshot();

    // then
    assertThat(before.versions()).endsWith(V_RECONCILIATION);
  }

  @Test
  @DisplayName("접근 코어 표를 모두 만든다")
  void createsAccessTables() {
    var scenario = new AccessCoreMigrationScenario("scos_access_tables");

    scenario.migrate();

    // then
    assertThat(scenario.publicTables())
        .contains(
            "document_acl",
            "users",
            "source_identities",
            "user_source_identities",
            "source_groups",
            "org_wide_scopes",
            "audit_events");
  }

  @Test
  @DisplayName("기존 소유 행은 유닛의 출처로 채워진다")
  void backfillsOwnershipSource() {
    var scenario = new AccessCoreMigrationScenario("scos_access_backfill");
    scenario.givenOwner("doc", "a");
    scenario.givenUnit("doc", "a", "slack-a");

    scenario.migrate();

    // then
    assertThat(scenario.sourceOf("doc")).contains("slack-a");
  }

  @Test
  @DisplayName("유닛 없는 소유 행은 지워진다")
  void removesOrphanOwnership() {
    var scenario = new AccessCoreMigrationScenario("scos_access_orphan");
    scenario.givenOwner("orphan", "a");
    scenario.givenOwner("kept", "a");
    scenario.givenUnit("kept", "a", "slack-a");

    scenario.migrate();

    // then
    assertThat(scenario.ownedDocuments()).containsExactly("kept");
  }

  @Test
  @DisplayName("출처가 둘인 문서가 있으면 마이그레이션이 실패하고 표는 그대로다")
  void conflictingSourcesRollBackMigration() {
    var scenario = new AccessCoreMigrationScenario("scos_access_conflict");
    scenario.givenOwner("doc", "a");
    scenario.givenUnit("doc", "a", "slack-a");
    scenario.givenUnit("doc", "b", "drive-a");
    var before = scenario.snapshot();

    // when·then
    assertThatThrownBy(scenario::migrate)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("출처가 둘 이상인 문서");
    assertThat(scenario.snapshot()).isEqualTo(before);
  }

  @Test
  @DisplayName("소유 출처 열은 NOT NULL이다")
  void ownershipSourceIsRequired() {
    var scenario = new AccessCoreMigrationScenario("scos_access_required");

    scenario.migrate();

    // then
    assertThat(scenario.sourceNullable()).isEqualTo("NO");
  }
}

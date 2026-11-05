package io.scos.collection.internal;

import static io.scos.collection.internal.AccessObservationStoreContract.T1;
import static io.scos.collection.internal.AccessObservationStoreContract.group;
import static io.scos.collection.internal.AccessObservationStoreContract.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.support.Eventually;
import io.scos.support.PgLocks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("db")
@DisplayName("PostgreSQL 접근 관측은 실제 트랜잭션 경계를 지킨다")
class AccessObservationPostgresTest {
  AccessObservationScenario scenario;
  JdbcClient jdbc;
  TransactionTemplate tx;

  @BeforeEach
  void prepare() {
    PostgresSupport.truncateAll();
    var ds = PostgresSupport.dataSource();
    scenario = AccessObservationScenario.postgres(ds);
    jdbc = JdbcClient.create(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  @Test
  @DisplayName("staging은 호출자 롤백으로 함께 사라진다")
  void stagingParticipatesInCallerTransaction() {
    tx.executeWithoutResult(
        status -> {
          scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
          status.setRollbackOnly();
        });
    // then
    assertThat(scenario.staged()).isEqualTo(AccessMergePolicy.Observations.empty());
  }

  @Test
  @DisplayName("병합의 자체 트랜잭션은 호출자의 롤백과 독립이다")
  void mergeOwnsTransaction() {
    scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
    tx.executeWithoutResult(
        status -> {
          scenario.merge(AccessMerge.COMPLETE);
          status.setRollbackOnly();
        });
    // then
    assertThat(scenario.group("C1").members()).containsExactly("U2");
  }

  @Test
  @DisplayName("그룹 쓰기 장애는 먼저 저장한 신원도 롤백한다")
  void storageFailureRollsBackIdentities() {
    scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
    rejectGroups();
    try {
      // then
      assertThatThrownBy(() -> scenario.merge(AccessMerge.COMPLETE))
          .isInstanceOf(DataAccessException.class);
    } finally {
      allowGroups();
    }
    // then
    assertThat(scenario.current()).isEqualTo(SourceAccessDirectory.Snapshot.empty());
  }

  @Test
  @DisplayName("저장 장애는 INCOMPLETE fallback으로 숨기지 않는다")
  void storageFailureDoesNotFallback() {
    scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
    AtomicInteger attempts = new AtomicInteger();
    rejectGroups();
    try {
      // then
      assertThatThrownBy(() -> scenario.merge(AccessMerge.COMPLETE, attempts::incrementAndGet))
          .isInstanceOf(DataAccessException.class);
    } finally {
      allowGroups();
    }
    // then
    assertThat(attempts.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("저장 장애 뒤에도 같은 보관값으로 재시도한다")
  void storageFailurePreservesStagingForRetry() {
    scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
    rejectGroups();
    try {
      // then
      assertThatThrownBy(() -> scenario.merge(AccessMerge.COMPLETE))
          .isInstanceOf(DataAccessException.class);
    } finally {
      allowGroups();
    }
    scenario.merge(AccessMerge.COMPLETE);
    // then
    assertThat(scenario.group("C1").members()).containsExactly("U2");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("첫 삽입과 기존 행의 staging 경합은 동시각 상충 교집합을 보존한다")
  void concurrentStageRestricts(boolean existing) throws Exception {
    if (existing) scenario.stage(group("C1", T1, "U1", "U2", "U3"));
    raceStage();
    // then
    assertThat(scenario.staged().snapshot().groups().get("C1").members()).containsExactly("U2");
  }

  @Test
  @DisplayName("COMPLETE rollback과 fallback 사이의 폐기는 관측한 회수를 유실시키지 않는다")
  void discardCannotEraseFallbackRestriction() throws Exception {
    scenario.incompleteSnapshot();
    mergeWhileDiscardingAtFallback();
    // then
    assertThat(scenario.group("C1").members()).containsExactly("U2");
  }

  void mergeWhileDiscardingAtFallback() throws Exception {
    var ds = PostgresSupport.dataSource();
    var attempts = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Void>> discards = new ArrayList<>();
      scenario.merge(
          AccessMerge.COMPLETE,
          () -> {
            if (attempts.incrementAndGet() != 2) return;
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            var discard = CompletableFuture.runAsync(scenario::discard, executor);
            discards.add(discard);
            try {
              Eventually.await(
                  "discard committed or is blocked by merge",
                  () -> discard.isDone() || PgLocks.anyBlockedBy(ds, pid),
                  Duration.ofSeconds(10));
            } catch (Exception failure) {
              throw new IllegalStateException("Discard did not reach the merge boundary", failure);
            }
          });
      discards.getFirst().get(10, TimeUnit.SECONDS);
    }
  }

  void raceStage() throws Exception {
    var ds = PostgresSupport.dataSource();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Void>> delayed = new ArrayList<>();
      tx.executeWithoutResult(
          status -> {
            scenario.stage(group("C1", T1, "U1", "U2"));
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            delayed.add(
                CompletableFuture.runAsync(
                    () -> scenario.stage(group("C1", T1, "U2", "U3")), executor));
            try {
              PgLocks.awaitBlockedBy(ds, pid, Duration.ofSeconds(10));
            } catch (Exception failure) {
              throw new IllegalStateException("Stage lock was not observed", failure);
            }
          });
      delayed.getFirst().get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("별도 병합 트랜잭션에서도 기존 DataSource로 실행한 guard 쓰기는 함께 롤백한다")
  void guardSharesMergeTransaction() {
    scenario.stage(group("C1", T1, "U2"), identity("U2", T1));
    rejectGroups();
    try {
      // then
      assertThatThrownBy(
              () ->
                  scenario.merge(
                      AccessMerge.COMPLETE,
                      () ->
                          jdbc.sql("UPDATE users SET active = false WHERE id = 'local-user'")
                              .update()))
          .isInstanceOf(DataAccessException.class);
    } finally {
      allowGroups();
    }
    // then
    assertThat(
            jdbc.sql("SELECT active FROM users WHERE id = 'local-user'")
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  void rejectGroups() {
    jdbc.sql(
            "ALTER TABLE source_groups ADD CONSTRAINT access_test_failure CHECK (group_id <> 'C1')")
        .update();
  }

  void allowGroups() {
    jdbc.sql("ALTER TABLE source_groups DROP CONSTRAINT access_test_failure").update();
  }
}

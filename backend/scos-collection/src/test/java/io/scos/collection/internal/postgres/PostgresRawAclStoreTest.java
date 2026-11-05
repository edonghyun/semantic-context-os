package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.RawAclStore;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.SourceQueries.RawCatalogQuery;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.PgLocks;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("db")
@Timeout(60)
@DisplayName("원본 ACL PostgreSQL 저장은 트랜잭션과 운영 조회에 연결된다")
class PostgresRawAclStoreTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  static final SourceInstanceId OTHER = new SourceInstanceId("drive-b");
  static final String ID = "file-1";
  static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  static final Duration LOCK_WAIT = Duration.ofSeconds(10);
  static final RawAcl.Observed GRANTED =
      RawAcl.observed(Status.OK, Set.of(AccessKey.parse("user:drive-a:alice@example.com")), T0);
  DatabaseScenario scenario;

  @BeforeEach
  void setUp() {
    PostgresSupport.truncateAll();
    scenario = new DatabaseScenario();
  }

  @Test
  @DisplayName("새 바이너리의 모르는 원본 실패 원인은 unknown으로 읽는다")
  void unknownStoredFailureReasonIsReadable() {
    scenario.store.observe(SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(), T0), T0);
    JdbcClient.create(PostgresSupport.dataSource())
        .sql("UPDATE raw_acl SET reason = 'FUTURE_POLICY'")
        .update();
    // then
    assertThat(scenario.store.get(SOURCE, List.of(ID)).get(ID).reason().wire())
        .isEqualTo("unknown");
  }

  @Test
  @DisplayName("호출자 롤백은 원본 ACL 삽입도 되돌린다")
  void insertJoinsCallerTransaction() {
    scenario.rollbackObservation();
    // then
    assertThat(scenario.store.get(SOURCE, List.of(ID))).isEmpty();
  }

  @Test
  @DisplayName("호출자 롤백은 원본 ACL 갱신도 되돌린다")
  void updateJoinsCallerTransaction() {
    scenario.store.observe(SOURCE, ID, GRANTED, T0);
    scenario.rollbackObservation();
    // then
    assertThat(scenario.store.get(SOURCE, List.of(ID))).containsEntry(ID, GRANTED);
  }

  @Test
  @DisplayName("원본 ACL 삭제도 호출자 롤백에 참여한다")
  void deleteJoinsCallerTransaction() {
    scenario.store.observe(SOURCE, ID, GRANTED, T0);
    scenario.tx.executeWithoutResult(
        status -> {
          scenario.store.delete(SOURCE, List.of(ID));
          status.setRollbackOnly();
        });
    // then
    assertThat(scenario.store.get(SOURCE, List.of(ID))).containsEntry(ID, GRANTED);
  }

  @Test
  @DisplayName("운영 raw get은 소스별 최신 ACL을 읽는다")
  void operationalGetReadsAcl() {
    scenario.givenRawItems();
    var record = scenario.queries.rawGet(SOURCE, ID).orElseThrow().record();
    // then
    assertThat(((RawRecord.Active) record).item().acl()).isEqualTo(GRANTED);
  }

  @Test
  @DisplayName("운영 raw list는 ACL 조인 뒤에도 소스별 두 원본만 읽는다")
  void operationalListPreservesRows() {
    scenario.givenRawItems();
    var page = scenario.catalog();
    // then
    assertThat(page.items())
        .extracting(SourceQueries.RawEntry::source)
        .containsExactly(SOURCE, OTHER);
  }

  @Test
  @DisplayName("운영 raw list는 다른 소스의 ACL을 섞지 않는다")
  void operationalListReadsAcl() {
    scenario.givenRawItems();
    var page = scenario.catalog();
    // then
    assertThat(page.items())
        .extracting(entry -> ((RawRecord.Active) entry.record()).item().acl())
        .containsExactly(GRANTED, new RawAcl.NotObserved());
  }

  @Test
  @DisplayName("첫 삽입이 경합해도 지연 관측이 최신 ACL을 되돌리지 못한다")
  void concurrentFirstObservationIsMonotonic() throws Exception {
    scenario.raceObservation();
    // then
    assertThat(scenario.store.get(SOURCE, List.of(ID)))
        .containsEntry(ID, new RawAcl.Observed(Status.FAILED, Set.of(), T0.plusSeconds(2)));
  }

  @Test
  @DisplayName("기존 행 갱신이 경합해도 지연 관측을 거부한다")
  void concurrentUpdateIsMonotonic() throws Exception {
    scenario.store.observe(SOURCE, ID, GRANTED, T0);
    scenario.raceObservation();
    // then
    assertThat(scenario.store.get(SOURCE, List.of(ID)))
        .containsEntry(ID, new RawAcl.Observed(Status.FAILED, Set.of(), T0.plusSeconds(2)));
  }

  @Test
  @DisplayName("잠금 대기 중 새 관측이 커밋되면 갱신 전에 다시 비교한다")
  void waitingObservationRechecksLockedRow() throws Exception {
    scenario.store.observe(SOURCE, ID, GRANTED, T0);
    scenario.raceAgainstLockedRow();
    // then
    assertThat(scenario.store.get(SOURCE, List.of(ID)))
        .containsEntry(ID, new RawAcl.Observed(Status.FAILED, Set.of(), T0.plusSeconds(2)));
  }

  static final class DatabaseScenario {
    final DataSource dataSource = PostgresSupport.dataSource();
    final JdbcClient jdbc = JdbcClient.create(dataSource);
    final RawAclStore store = PostgresCollectionConfiguration.adapters(dataSource).rawAcls();
    final TransactionTemplate tx =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    final PostgresCollectionConfiguration.Adapters adapters =
        PostgresCollectionConfiguration.adapters(dataSource);
    final SourceQueries queries = adapters.queries();

    void rollbackObservation() {
      tx.executeWithoutResult(
          status -> {
            store.observe(
                SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(), T0.plusSeconds(2)), T0);
            status.setRollbackOnly();
          });
    }

    void givenRawItems() {
      adapters.rawItems().apply(SOURCE, RawItems.item(ID).upsert());
      adapters.rawItems().apply(OTHER, RawItems.item(ID).upsert());
      store.observe(SOURCE, ID, GRANTED, T0);
    }

    SourceQueries.RawCatalogPage catalog() {
      return queries.rawList(
          new RawCatalogQuery(
              10, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    void raceAgainstLockedRow() throws Exception {
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<Boolean>> delayed = new ArrayList<>();
        tx.executeWithoutResult(
            status -> {
              jdbc.sql(
                      "SELECT external_id FROM raw_acl WHERE source_instance_id = :source AND external_id = :id FOR UPDATE")
                  .param("source", SOURCE.value())
                  .param("id", ID)
                  .query(String.class)
                  .single();
              int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
              delayed.add(
                  CompletableFuture.supplyAsync(
                      () ->
                          store.observe(
                              SOURCE,
                              ID,
                              RawAcl.observed(Status.OK, Set.of(), T0.plusSeconds(1)),
                              T0),
                      executor));
              try {
                PgLocks.awaitBlockedBy(dataSource, pid, LOCK_WAIT);
              } catch (Exception failure) {
                throw new IllegalStateException("관측 잠금 경합을 확인하지 못했다", failure);
              }
              store.observe(
                  SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(), T0.plusSeconds(2)), T0);
            });
        delayed.getFirst().get(10, TimeUnit.SECONDS);
      }
    }

    void raceObservation() throws Exception {
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<Boolean>> delayed = new ArrayList<>();
        tx.executeWithoutResult(
            status -> {
              store.observe(
                  SOURCE, ID, RawAcl.observed(Status.FAILED, Set.of(), T0.plusSeconds(2)), T0);
              int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
              delayed.add(
                  CompletableFuture.supplyAsync(
                      () ->
                          store.observe(
                              SOURCE,
                              ID,
                              RawAcl.observed(Status.OK, GRANTED.keys(), T0.plusSeconds(1)),
                              T0),
                      executor));
              try {
                PgLocks.awaitBlockedBy(dataSource, pid, LOCK_WAIT);
              } catch (Exception failure) {
                throw new IllegalStateException("관측 잠금 경합을 확인하지 못했다", failure);
              }
            });
        delayed.getFirst().get(10, TimeUnit.SECONDS);
      }
    }
  }
}

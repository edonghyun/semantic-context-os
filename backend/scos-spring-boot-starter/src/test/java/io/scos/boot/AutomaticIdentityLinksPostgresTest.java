package io.scos.boot;

import io.scos.collection.internal.AccessMerge;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.AutomaticIdentityLinksContract;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.engine.admin.IdentityReconciler;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.IdentityCoverage;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.postgres.AdminStorageScenario;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("db")
@DisplayName("PostgreSQL 자동 연결은 공통 정책과 원자성을 지킨다(§7.3)")
class AutomaticIdentityLinksPostgresTest extends AutomaticIdentityLinksContract {
  @Override
  protected Scenario newScenario() {
    PostgresSupport.truncateAll();
    return new PostgresScenario(new AdminStorageScenario(PostgresSupport.dataSource()));
  }

  private static final class PostgresScenario extends Scenario {
    private final AdminStorageScenario storage;
    private final AdminPort port;
    private final IdentityReconciler reconciler;

    private PostgresScenario(AdminStorageScenario storage) {
      super(storage.store(), storage.identityReader(), storage.authentication());
      this.storage = storage;
      port =
          AdminConfiguration.newAdminPort(
              serviceStore(), CLOCK, POLICY, new FixedAdminDiagnostics());
      reconciler = AdminConfiguration.newIdentityReconciler(serviceStore(), CLOCK, POLICY);
    }

    @Override
    public AdminPort port() {
      return port;
    }

    @Override
    public IdentityReconciler reconciler() {
      return reconciler;
    }

    @Override
    public void registerSource(SourceAccount source) {
      storage.registerSource(source);
    }

    @Override
    public void observe(SourceInstanceId source, SourceIdentityRecord identity) {
      storage.observeIdentity(source, identity);
    }

    @Override
    protected void mergeDirectory(SourceIdentityRecord identity, Runnable beforeCommit) {
      var adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
      var run = new RunId("automatic-directory-merge");
      adapters.runs().start(run, SLACK, AT);
      adapters.accessObservations().stage(run, new AclPage(Map.of(), List.of(), List.of(identity)));
      adapters
          .accessObservations()
          .merge(
              run,
              SLACK,
              AccessMerge.COMPLETE,
              IdentityCoverage.PARTIAL,
              identity.observedAt(),
              () ->
                  TransactionSynchronizationManager.registerSynchronization(
                      new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                          beforeCommit.run();
                        }
                      }));
    }
  }
}

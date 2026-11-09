package io.scos.engine.internal.admin;

import io.scos.collection.internal.AccessMerge;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.AutomaticIdentityLinksContract;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.engine.admin.IdentityReconciler;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.IdentityCoverage;
import io.scos.spi.source.SourceAccessDirectory.Snapshot;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.memory.MemoryAdminStore;
import io.scos.store.memory.MemorySourceAccessDirectory;
import io.scos.store.memory.MemoryUserTokens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;

@DisplayName("메모리 자동 연결은 공통 정책과 원자성을 지킨다(§7.3)")
class AutomaticIdentityLinksTest extends AutomaticIdentityLinksContract {
  @Override
  protected Scenario newScenario() {
    return new MemoryScenario(MemoryRunStore.create());
  }

  private static final class MemoryScenario extends Scenario {
    private final MemoryRunStore runs;
    private final MemorySourceAccessDirectory directory;
    private final AdminPort port;
    private final IdentityReconciler reconciler;

    private MemoryScenario(MemoryRunStore runs) {
      this(
          runs,
          new MemoryUserTokens(runs.identities()),
          new MemorySourceAccessDirectory(runs.identities()));
    }

    private MemoryScenario(
        MemoryRunStore runs, MemoryUserTokens tokens, MemorySourceAccessDirectory directory) {
      super(new MemoryAdminStore(runs.identities(), tokens, directory), runs.identities(), tokens);
      this.runs = runs;
      this.directory = directory;
      port = new AdminService(serviceStore(), CLOCK, new FixedAdminDiagnostics());
      reconciler = new AutomaticIdentityLinks(serviceStore(), CLOCK, POLICY);
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
      runs.identities().registerSource(source);
    }

    @Override
    public void observe(SourceInstanceId source, SourceIdentityRecord identity) {
      directory.save(source, new Snapshot(Map.of(identity.sourceUserId(), identity), Map.of()));
    }

    @Override
    protected void mergeDirectory(SourceIdentityRecord identity, Runnable beforeCommit) {
      var run = new RunId("automatic-directory-merge");
      runs.start(run, SLACK, AT);
      runs.accessObservations().stage(run, new AclPage(Map.of(), List.of(), List.of(identity)));
      directory.withLock(
          () -> {
            runs.accessObservations()
                .merge(
                    run,
                    SLACK,
                    AccessMerge.COMPLETE,
                    IdentityCoverage.PARTIAL,
                    identity.observedAt(),
                    () -> {});
            beforeCommit.run();
          });
    }
  }
}

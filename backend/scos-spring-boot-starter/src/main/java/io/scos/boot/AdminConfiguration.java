package io.scos.boot;

import io.scos.engine.admin.AdminBootstrap;
import io.scos.engine.admin.AdminDiagnostics;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.IdentityReconciler;
import io.scos.engine.internal.admin.AdminService;
import io.scos.engine.internal.admin.AutomaticIdentityLinks;
import io.scos.engine.internal.admin.BootstrapAdmin;
import io.scos.spi.access.AdminStore;
import io.scos.spi.access.DomainPolicy;
import java.time.Clock;

/** 관리 포트를 저장 경계와 시계로 조립하는 순수 공용 팩터리. */
public final class AdminConfiguration {
  private AdminConfiguration() {}

  public static AdminBootstrap newAdminBootstrap(AdminStore store, Clock clock) {
    return new BootstrapAdmin(store, clock);
  }

  public static IdentityReconciler newIdentityReconciler(
      AdminStore store, Clock clock, DomainPolicy domains) {
    return new AutomaticIdentityLinks(store, clock, domains);
  }

  public static AdminPort newAdminPort(
      AdminStore store, Clock clock, DomainPolicy domainPolicy, AdminDiagnostics diagnostics) {
    return new AdminService(store, clock, diagnostics);
  }
}

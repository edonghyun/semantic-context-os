package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.SourceRegistrar;
import io.scos.collection.internal.SourceSchedule;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.engine.admin.AdminPort;
import io.scos.engine.admin.AdminScopesContract;
import io.scos.engine.admin.FixedAdminDiagnostics;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.spi.access.AdminRecords.SourceAccount;
import io.scos.spi.connector.AccessModel;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import io.scos.spi.source.SourceInstanceId;
import io.scos.store.postgres.AdminStorageScenario;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("db")
@DisplayName("PostgreSQL 조직 전체 지정은 공통 정책을 지킨다(§7.1)")
class AdminScopesPostgresTest extends AdminScopesContract {
  @Override
  protected Scenario newScenario() {
    PostgresSupport.truncateAll();
    return new ScopeScenario(new AdminStorageScenario(PostgresSupport.dataSource()));
  }

  @Nested
  @DisplayName("실제 Notion과 Jira 어댑터로 소스를 등록한 상태에서")
  class RegisteredAdapters {
    @BeforeEach
    void registerAdapters() {
      ((ScopeScenario) scenario).givenRegisteredAdapters();
    }

    @Test
    @DisplayName("등록된 Notion을 지정하면 해당 인스턴스만 공개한다")
    void registeredNotionRemainsOnlyDesignatableSource() {
      // when
      var designation = scenario.port().designateScope(ADMIN, NOTION);
      // then
      assertThat(designation.source().value()).isEqualTo("notion-a");
      assertThat(scenario.scopes())
          .extracting(scope -> scope.source().value())
          .containsExactly("notion-a");
    }

    @Test
    @DisplayName("등록된 supported Jira를 지정하면 invalid_request이다")
    void registeredSupportedJiraCannotBeDesignated() {
      // when
      var failure =
          catchThrowable(
              () -> scenario.port().designateScope(ADMIN, new SourceInstanceId("jira-a")));
      // then
      assertThat(failure).isInstanceOf(AdminPort.Failure.class).hasMessage("invalid_request");
      assertThat(scenario.scopes()).isEmpty();
      assertThat(scenario.audit("scope.designate")).isEmpty();
    }
  }

  private static final class ScopeScenario extends Scenario {
    private final AdminPort port;
    private final SourceInstanceStore sources;

    private ScopeScenario(AdminStorageScenario storage) {
      super(
          storage.store(),
          storage.identityReader(),
          new PostgresIndexConfiguration().newAclStore(PostgresSupport.dataSource()));
      port =
          AdminConfiguration.newAdminPort(
              serviceStore(), CLOCK, POLICY, new FixedAdminDiagnostics());
      sources = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource()).sources();
    }

    @Override
    public AdminPort port() {
      return port;
    }

    @Override
    public void registerSource(SourceAccount source) {
      sources.save(
          SourceInstances.of(source.id(), source.kind(), "{}", "env:CONTRACT", CLOCK.instant()));
    }

    private void givenRegisteredAdapters() {
      var json = new ObjectMapper();
      CredentialResolver credentials =
          ref -> {
            throw new IllegalStateException("credential resolution forbidden in registration");
          };
      var meters = new SimpleMeterRegistry();
      try (var http = new JiraCollectionConfiguration().jiraHttpClient()) {
        var calls = ProviderCallExecutor.defaults(CLOCK, ProviderCallMetrics.of(meters));
        var notion =
            new NotionCollectionConfiguration()
                .notionAdapter(
                    new NotionCollectionConfiguration.NotionProperties("http://127.0.0.1:1"),
                    credentials,
                    calls,
                    json,
                    CLOCK);
        var jiraConfiguration = new JiraCollectionConfiguration();
        var jira =
            jiraConfiguration.jiraAdapter(
                credentials,
                jiraConfiguration.jiraApiClient(http, calls, json, CLOCK),
                json,
                CLOCK);
        registerAndVerifyPolicies(notion, jira);
      } finally {
        meters.close();
      }
    }

    private void registerAndVerifyPolicies(SourceAdapter notion, SourceAdapter jira) {
      var registrar = new SourceRegistrar(sources, SourceAdapters.of(List.of(notion, jira)), CLOCK);
      var notionSource =
          registrar.register(
              NOTION,
              notion.kind(),
              "Contract Notion",
              new SourceConfig(1, "{}"),
              "env:CONTRACT",
              SourceSchedule.DAEMON_DEFAULTS);
      var jiraSource =
          registrar.register(
              new SourceInstanceId("jira-a"),
              jira.kind(),
              "Contract Jira",
              new SourceConfig(
                  1,
                  "{\"siteUrl\":\"https://example.atlassian.net\",\"email\":\"admin@example.com\"}"),
              "env:CONTRACT",
              SourceSchedule.DAEMON_DEFAULTS);
      if (notion.accessPolicy().model() != AccessModel.UNSUPPORTED
          || !notion.accessPolicy().permitsScope(notionSource.descriptor())
          || jira.accessPolicy().model() != AccessModel.SUPPORTED
          || jira.accessPolicy().permitsScope(jiraSource.descriptor()))
        throw new IllegalStateException("given 준비 실패: 실제 어댑터의 scoped/supported 선언 불일치");
    }
  }
}

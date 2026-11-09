package io.scos.server;

import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.postgres.PostgresSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

@Tag("db")
@DisplayName("실제 HTTP와 PostgreSQL에서도 관리 경계를 지킨다")
class AdminHttpPostgresTest extends AdminHttpContract {
  @Override
  AdminHttpScenario newScenario(boolean multi) throws Exception {
    PostgresSupport.truncateAll();
    var database = PostgresSupport.dataSource();
    var properties = new DataSourceProperties();
    properties.setUrl(PostgresSupport.jdbcUrl());
    properties.setUsername(PostgresSupport.username());
    properties.setPassword(PostgresSupport.password());
    return new AdminHttpScenario(
        multi,
        new PostgresIndexConfiguration().newAdminStore(database),
        new AdminHttpScenario.Database(database, properties));
  }
}

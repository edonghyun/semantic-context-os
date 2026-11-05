package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AclStore;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.access.UserTokens;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("PostgreSQL 검색 구성은 접근 저장소 빈을 제공한다")
class PostgresIndexConfigurationTest {
  @Test
  @DisplayName("ACL 저장소 빈을 제공한다")
  void providesAclStore() {
    // when·then
    context().run(context -> assertThat(context).hasSingleBean(AclStore.class));
  }

  @Test
  @DisplayName("신원 저장소 빈을 제공한다")
  void providesIdentityStore() {
    // when·then
    context().run(context -> assertThat(context).hasSingleBean(IdentityStore.class));
  }

  @Test
  @DisplayName("사용자 토큰 저장소는 명시적 조립 전까지 등록하지 않는다")
  void leavesUserTokensForExplicitAssembly() {
    // when·then
    context().run(context -> assertThat(context).doesNotHaveBean(UserTokens.class));
  }

  private ApplicationContextRunner context() {
    var dataSource = new DriverManagerDataSource();
    return new ApplicationContextRunner()
        .withBean(DataSource.class, () -> dataSource)
        .withBean(
            PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
        .withUserConfiguration(PostgresIndexConfiguration.class);
  }
}

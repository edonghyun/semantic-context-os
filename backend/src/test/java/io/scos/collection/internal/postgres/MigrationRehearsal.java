package io.scos.collection.internal.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 마이그레이션 리허설(테스트 공용): 공용 컨테이너 안에 빈 데이터베이스를 새로 만들고, 제품 마이그레이션을 특정 버전까지 올린 뒤 그 시점 형태의 행을 넣고, 이어서 최신까지
 * 올린다(ADR-0020 §6). 데이터 이전 리허설(F9)도 이 순서를 쓴다.
 *
 * <pre>
 * MigrationRehearsal rehearsal = MigrationRehearsal.freshDatabase("scos_rehearsal").migrateTo("1");
 * rehearsal.jdbc().sql("INSERT …").update();
 * rehearsal.migrateToLatest();
 * </pre>
 */
public final class MigrationRehearsal {
  private static final String LOCATIONS = "classpath:db/migration";

  private final DriverManagerDataSource dataSource;

  private MigrationRehearsal(DriverManagerDataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** 공용 컨테이너에 이 이름의 빈 데이터베이스를 (다시) 만든다. */
  public static MigrationRehearsal freshDatabase(String name) {
    try (Connection c = PostgresSupport.dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("DROP DATABASE IF EXISTS " + name);
      st.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException("리허설 데이터베이스를 만들지 못했다: " + name, e);
    }
    String url = PostgresSupport.jdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/" + name + "$1");
    return new MigrationRehearsal(
        new DriverManagerDataSource(url, PostgresSupport.username(), PostgresSupport.password()));
  }

  /** 이 버전까지만 제품 마이그레이션을 적용한다. */
  public MigrationRehearsal migrateTo(String version) {
    Flyway.configure().dataSource(dataSource).locations(LOCATIONS).target(version).load().migrate();
    return this;
  }

  public MigrationRehearsal migrateToLatest() {
    latest().migrate();
    return this;
  }

  /** 최신 마이그레이션 기준의 검증(체크섬·누락). 실패하면 예외다. */
  public void validate() {
    latest().validate();
  }

  public DataSource dataSource() {
    return dataSource;
  }

  public JdbcClient jdbc() {
    return JdbcClient.create(dataSource);
  }

  private Flyway latest() {
    return Flyway.configure().dataSource(dataSource).locations(LOCATIONS).load();
  }
}

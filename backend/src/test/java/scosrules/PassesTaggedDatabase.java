package scosrules;

import io.scos.collection.internal.postgres.PostgresSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

/** 표본(F15 T15.2): 데이터베이스를 쓰고 @Tag("db")가 있다 — databaseTestsAreTagged에 걸리지 않아야 한다. */
@Tag("db")
abstract class PassesTaggedDatabase {
  static DataSource db() {
    return PostgresSupport.dataSource();
  }
}

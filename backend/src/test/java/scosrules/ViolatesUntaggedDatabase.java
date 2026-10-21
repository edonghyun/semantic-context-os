package scosrules;

import io.scos.collection.internal.postgres.PostgresSupport;
import javax.sql.DataSource;

/** 표본(F15 T15.2): 데이터베이스를 쓰는데 @Tag("db")가 없다 — databaseTestsAreTagged에 걸려야 한다. */
abstract class ViolatesUntaggedDatabase {
  static DataSource db() {
    return PostgresSupport.dataSource();
  }
}

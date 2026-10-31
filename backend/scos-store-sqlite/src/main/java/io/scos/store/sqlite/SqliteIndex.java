package io.scos.store.sqlite;

import io.scos.spi.access.AclStore;
import io.scos.spi.access.IdentityStore;
import io.scos.spi.store.EmbeddingCache;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.UnitMaintenance;
import io.scos.spi.store.UnitStore;
import io.scos.spi.store.VectorIndex;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite 한 파일(index.sqlite)에 엔진 저장 포트를 올리는 조립 지점. 연결은 호출마다 열고 닫으며(WAL이라 읽기는 쓰기와 겹친다), 여러 어댑터의 쓰기는
 * {@link #transactions()} 한 트랜잭션에 참여한다.
 */
public final class SqliteIndex implements AutoCloseable {
  static final String MIGRATIONS = "db/sqlite/index";

  private final TransactionOperations transactions;
  private final SpaceMeta meta;
  private final EmbeddingCache embeddingCache;
  private final AclStore acl;
  private final IdentityStore identities;
  private final SqliteUnitStore units;
  private final UnitMaintenance maintenance;
  private final SqliteVectorIndex vectors;
  private final KeywordIndex keywords;

  private SqliteIndex(DataSource dataSource) {
    this.transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    JdbcClient jdbc = JdbcClient.create(dataSource);
    this.meta = new SqliteSpaceMeta(jdbc);
    this.embeddingCache = new SqliteEmbeddingCache(jdbc, transactions);
    this.acl = new SqliteAclStore(jdbc, transactions);
    this.identities = new SqliteIdentityStore(jdbc);
    this.units = new SqliteUnitStore(jdbc, transactions);
    this.vectors = new SqliteVectorIndex(jdbc, transactions);
    this.maintenance = new SqliteUnitMaintenance(units, vectors);
    this.keywords = new SqliteKeywordIndex(jdbc, transactions);
  }

  /** 파일을 열고(상위 폴더는 만든다) 스키마를 적용한다. 더 새 스키마의 파일은 {@link IllegalStateException}. */
  public static SqliteIndex open(Path file) {
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) Files.createDirectories(parent);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    SQLiteConfig config = new SQLiteConfig();
    config.setBusyTimeout(SqliteMigrations.BUSY_MILLIS);
    config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
    config.enforceForeignKeys(true);
    SQLiteDataSource dataSource = new SQLiteDataSource(config);
    dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
    try {
      new SqliteMigrations(dataSource, MIGRATIONS, "index.sqlite").apply();
      return new SqliteIndex(dataSource);
    } catch (SQLException e) {
      throw new IllegalStateException("index.sqlite schema migration failed", e);
    }
  }

  public SpaceMeta meta() {
    return meta;
  }

  public EmbeddingCache embeddingCache() {
    return embeddingCache;
  }

  public AclStore acl() {
    return acl;
  }

  public IdentityStore identities() {
    return identities;
  }

  public UnitStore units() {
    return units;
  }

  public UnitMaintenance maintenance() {
    return maintenance;
  }

  public VectorIndex vectors() {
    return vectors;
  }

  public KeywordIndex keywords() {
    return keywords;
  }

  /** 같은 DataSource의 트랜잭션 — 어댑터는 JdbcClient로 참여한다. */
  public TransactionOperations transactions() {
    return transactions;
  }

  /** 연결을 호출마다 닫으므로 풀어 줄 자원이 없다. */
  @Override
  public void close() {}
}

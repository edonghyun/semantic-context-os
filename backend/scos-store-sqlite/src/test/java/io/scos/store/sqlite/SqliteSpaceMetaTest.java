package io.scos.store.sqlite;

import io.scos.spi.store.SpaceMeta;
import io.scos.spi.store.SpaceMetaContract;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("공간 메타 계약 — SQLite 포트(ADR-0018 §6)")
class SqliteSpaceMetaTest extends SpaceMetaContract {
  @TempDir Path dir;

  @Override
  protected SpaceMeta newMeta() {
    return SqliteIndex.open(dir.resolve("index.sqlite")).meta();
  }
}

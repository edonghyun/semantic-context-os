package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 테스트 정리(truncateAll)가 되돌리는 시드 행 목록은 마이그레이션의 데이터 시드(INSERT … VALUES)와 같다(TESTING §6.1). */
@DisplayName("정리가 되돌리는 시드 행은 마이그레이션의 시드와 같다(TESTING §6.1)")
@Tag("db")
class MigrationSeedsTest {
  static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  static final Pattern INSERT_VALUES =
      Pattern.compile("(?is)INSERT\\s+INTO\\s+[^;]*?\\bVALUES\\b[^;]*");

  static List<String> migrationSeeds() throws IOException {
    List<String> seeds = new ArrayList<>();
    try (Stream<Path> files = Files.list(MIGRATIONS)) {
      for (Path f :
          files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList()) {
        Matcher m = INSERT_VALUES.matcher(Files.readString(f));
        while (m.find()) seeds.add(m.group().replaceAll("\\s+", " ").trim());
      }
    }
    return seeds;
  }

  @Test
  @DisplayName("마이그레이션의 시드 행과 PostgresSupport.MIGRATION_SEEDS가 같다")
  void seedsMatchMigrations() throws IOException {
    // given: INSERT … VALUES 시드 — 현재 V13 raw_change_retention 1행
    List<String> seeds = migrationSeeds();
    if (seeds.isEmpty()) throw new IllegalStateException("given 준비 실패: 시드가 하나 이상 있어야 한다");

    // when·then
    assertThat(PostgresSupport.MIGRATION_SEEDS).containsExactlyElementsOf(seeds);
  }
}

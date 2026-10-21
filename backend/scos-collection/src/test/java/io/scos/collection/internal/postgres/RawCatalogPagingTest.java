package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.RawCatalogCursor;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.SourceQueries.RawCatalogQuery;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 원본 카탈로그 쪽 넘김(raw list --cursor). TS는 밀리초 문자열로 비교하지만 PostgreSQL은 마이크로초까지 저장한다 — 커서가 시각을 잘라 내면 같은
 * 밀리초 안의 항목을 건너뛴다. 밀리초 시각의 순서·커서 바이트는 TS 비교(ops.QueryCommandsTsEquivalenceTest)가 본다.
 */
@DisplayName("원본 카탈로그 커서는 마이크로초 시각을 잘라 내지 않는다")
@Tag("db")
class RawCatalogPagingTest {
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-paging");
  static final List<String> SAME_MILLISECOND =
      List.of(
          "2026-10-16T01:02:03.456001Z",
          "2026-10-16T01:02:03.456002Z",
          "2026-10-16T01:02:03.456003Z");

  @Test
  @DisplayName("같은 밀리초 안의 항목도 한 건씩 넘기면 빠짐·중복 없이 읽힌다")
  void pagesWithinMillisecond() {
    // given: 같은 밀리초 안 마이크로초만 다른 원본 셋
    PostgresSupport.truncateAll();
    var adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
    for (int i = 0; i < SAME_MILLISECOND.size(); i++)
      adapters
          .rawItems()
          .apply(
              SOURCE,
              new RawMutation.Upsert(
                  RawItem.minimal(
                      "item-" + i, "v1", "{}", Instant.parse(SAME_MILLISECOND.get(i)))));

    // when
    List<String> read = readOneByOne(adapters.queries());

    // then
    assertThat(read).containsExactly("item-2", "item-1", "item-0");
  }

  /** limit 1로 nextCursor를 따라 끝까지 읽은 externalId. */
  static List<String> readOneByOne(SourceQueries queries) {
    List<String> ids = new ArrayList<>();
    Optional<RawCatalogCursor> cursor = Optional.empty();
    do {
      var page =
          queries.rawList(
              new RawCatalogQuery(1, cursor, Optional.empty(), Optional.empty(), Optional.empty()));
      page.items().forEach(entry -> ids.add(entry.record().externalId()));
      cursor = page.nextCursor().map(c -> RawCatalogCursor.decode(c.encode()));
    } while (cursor.isPresent());
    return ids;
  }
}

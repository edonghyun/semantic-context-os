package io.scos.store.postgres;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.store.SpaceId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("문서 소유 행은 첫 유닛의 출처를 기록하고 보존한다")
class PostgresUnitStoreSourceTest {
  private static final SpaceId SPACE = new SpaceId("a");
  private DocumentOwnershipScenario scenario;

  @BeforeEach
  void givenStore() {
    scenario = new DocumentOwnershipScenario();
  }

  @Test
  @DisplayName("새 문서의 소유 행은 첫 유닛의 출처를 기록한다")
  void recordsFirstUnitSource() {
    var first = unit("first").inDocument("doc").source("slack-a").build();
    var second = unit("second").inDocument("doc").source("drive-a").build();

    scenario.store().upsert(SPACE, List.of(first, second));

    // then
    assertThat(scenario.sourceOf("doc")).contains("slack-a");
  }

  @Test
  @DisplayName("이미 있는 소유 행의 출처는 다른 출처의 upsert로 바뀌지 않는다")
  void preservesExistingOwnerSource() {
    scenario
        .store()
        .upsert(SPACE, List.of(unit("first").inDocument("doc").source("slack-a").build()));
    var other = unit("other").inDocument("doc").source("drive-a").build();

    scenario.store().upsert(SPACE, List.of(other));

    // then
    assertThat(scenario.sourceOf("doc")).contains("slack-a");
  }

  @Test
  @DisplayName("같은 배치의 다른 문서는 각각 자기 출처를 기록한다")
  void recordsEachDocumentSource() {
    var first = unit("first").inDocument("doc-a").source("slack-a").build();
    var other = unit("other").inDocument("doc-b").source("drive-a").build();

    scenario.store().upsert(SPACE, List.of(first, other));

    // then
    assertThat(scenario.sourceOf("doc-b")).contains("drive-a");
  }
}

package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("명시적인 로컬 ACL 선언은 원격 대상 보충을 대신한다(§5.2)")
class LocalAccessCollectionTest {
  private static final String LEXICOGRAPHIC_LAST_ID = "file:F99";
  private final AccessCollectionScenario scenario = new AccessCollectionScenario("custom-local");

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("로컬 관측을 저장한 carrier는 수집기 원격 대상에서 제외한다")
  void localObservationCompletesTarget() {
    // given
    scenario.active("file:F1", "gdrive.file");
    // when
    scenario.collect();
    // then
    assertThat(scenario.acls("file:F1"))
        .containsEntry(
            "file:F1",
            RawAcl.observed(
                Status.OK,
                Set.of(AccessKey.parse("group:access-source:G1")),
                AccessCollectionScenario.T1));
    assertThat(scenario.targets()).isEmpty();
  }

  @ParameterizedTest(name = "대상 {0}")
  @ValueSource(ints = {500, 501})
  @DisplayName("500개 배치 경계의 마지막 로컬 관측도 원격 보충 없이 저장한다")
  void localObservationCrossesBatchBoundary(int count) {
    // given
    scenario.activeFiles(count);
    // when
    scenario.collect();
    // then
    assertThat(scenario.acls("file:F0", LEXICOGRAPHIC_LAST_ID))
        .containsEntry(
            "file:F0",
            RawAcl.observed(
                Status.OK,
                Set.of(AccessKey.parse("group:access-source:G1")),
                AccessCollectionScenario.T1))
        .containsEntry(
            LEXICOGRAPHIC_LAST_ID,
            RawAcl.observed(
                Status.OK,
                Set.of(AccessKey.parse("group:access-source:G1")),
                AccessCollectionScenario.T1));
    assertThat(scenario.targets()).isEmpty();
  }
}

package io.scos.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SQLite 벡터 삭제는 실패하면 이전 상태를 보존한다(B12)")
class SqliteVectorDeletionTest {
  private SqliteDeletionScenario scenario;

  @BeforeEach
  void givenIndexedDocumentAndDeleteFailure(@TempDir Path directory) throws Exception {
    scenario = new SqliteDeletionScenario(directory);
    scenario.givenIndexedDocument();
    scenario.givenFailureDuringVectorDeletion();
  }

  @AfterEach
  void closeScenario() {
    scenario.close();
  }

  @Test
  @DisplayName("단독 벡터 삭제 도중 실패하면 먼저 지운 벡터도 되돌린다")
  void rollsBackStandaloneDeletion() {
    // when
    var failure = scenario.deleteVectorsExpectingFailure();

    // then
    assertThat(failure).hasMessageContaining("injected vector delete failure");
    assertThat(scenario.vectorIds()).containsExactly("u1", "u2");
  }

  @Test
  @DisplayName("문서 삭제의 벡터 단계가 실패하면 유닛과 두 색인을 되돌린다")
  void rollsBackDocumentDeletion() {
    // when
    var failure = scenario.deleteDocumentExpectingFailure();

    // then
    assertThat(failure).hasMessageContaining("injected vector delete failure");
    assertThat(List.of(scenario.unitIds(), scenario.keywordIds(), scenario.vectorIds()))
        .containsExactly(List.of("u1", "u2"), List.of("u1", "u2"), List.of("u1", "u2"));
  }
}

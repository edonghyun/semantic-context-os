package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("db")
@DisplayName("동일 투영은 소유·ACL을 재작성하지 않고 커밋을 제한한다(P-2·P-3)")
class ProjectionWriteTest {
  @ParameterizedTest(name = "collect={0}")
  @ValueSource(booleans = {false, true})
  @DisplayName("100개 동일 문서의 커밋은 본문 100개와 소유·ACL 배치 하나다")
  void ownershipAndAclUseOneBatchCommit(boolean access) {
    try (var scenario = new ProjectionWriteScenario()) {
      scenario.givenIdenticalProjection(access);
      scenario.project(access);
      // then
      assertThat(scenario.commits()).isEqualTo(101);
    }
  }

  @ParameterizedTest(name = "collect={0}")
  @ValueSource(booleans = {false, true})
  @DisplayName("동일 재실행의 소유와 문서 ACL 행 쓰기는 0이다")
  void identicalProjectionWritesNoOwnershipOrAclRows(boolean access) {
    try (var scenario = new ProjectionWriteScenario()) {
      scenario.givenIdenticalProjection(access);
      scenario.project(access);
      // then
      assertThat(scenario.writtenRows()).isZero();
    }
  }
}

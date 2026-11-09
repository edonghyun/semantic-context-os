package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SourceQueries.AccessStatus;
import io.scos.collection.internal.SourceQueries.RunCost;
import io.scos.spi.connector.AccessCost;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("접근 상태 JSON은 본문과 독립된 보충·장기 미관측 상태를 내보낸다")
class SourceQueryJsonAccessTest {
  private final ObjectMapper json = new ObjectMapper();
  private final SourceQueryJson render = new SourceQueryJson(json);

  @Test
  @DisplayName("접근 미완료와 시각·누락·장기 미관측 수를 명시한다")
  void rendersIncompleteAccess() {
    var status =
        new AccessStatus(
            Optional.of(AccessResult.INCOMPLETE),
            Optional.of(Instant.parse("2026-10-14T00:00:01Z")),
            2,
            1,
            1,
            3,
            4,
            Optional.of(Instant.parse("2026-10-13T00:00:00Z")),
            false,
            2,
            Optional.of(Instant.parse("2026-10-12T00:00:00Z")),
            3,
            Set.of("file:F1"),
            Optional.empty());
    // then
    assertThat(render.accessStatus(status))
        .isEqualTo(
            json.readTree(
                """
        {"result":"incomplete","finishedAt":"2026-10-14T00:00:01.000Z",
         "rawUnknown":2,"rawFailed":1,"documentsMissingAcl":1,"rawUnobserved":3,"rawStale":4,
         "oldestObservedAt":"2026-10-13T00:00:00.000Z",
         "consecutiveIncomplete":2,"lastCompleteAt":"2026-10-12T00:00:00.000Z",
         "documentsHidden":3,"aclUnreadable":1,"aclUnreadableFiles":["file:F1"]}
        """));
  }

  @Test
  @DisplayName("실행 전 접근 상태는 결과와 시각을 생략한다")
  void rendersNeverRunWithoutInventedResult() {
    // then
    assertThat(
            render.accessStatus(
                new AccessStatus(
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    Optional.empty(),
                    false,
                    0,
                    Optional.empty(),
                    0,
                    Set.of(),
                    Optional.empty())))
        .isEqualTo(
            json.readTree(
                "{\"rawUnknown\":0,\"rawFailed\":0,\"documentsMissingAcl\":0,\"rawUnobserved\":0,\"rawStale\":0,\"consecutiveIncomplete\":0,\"documentsHidden\":0,\"aclUnreadable\":0,\"aclUnreadableFiles\":[]}"));
  }

  @Test
  @DisplayName("측정 비용은 결과 run 신원과 기록 시각을 JSON에 포함한다")
  void accessJsonIncludesRunCostIdentity() {
    // given
    var status = measuredStatus(OptionalLong.of(4));
    // when
    var output = render.accessStatus(status).toString();
    // then
    assertThat(output)
        .isEqualTo(
            """
        {"result":"complete","finishedAt":"2026-10-14T00:00:01.000Z","consecutiveIncomplete":0,"lastCompleteAt":"2026-10-14T00:00:01.000Z","documentsHidden":0,"aclUnreadable":0,"aclUnreadableFiles":[],"rawUnknown":0,"rawFailed":0,"documentsMissingAcl":0,"rawUnobserved":0,"rawStale":0,"cost":{"runId":"run-r1","recordedAt":"2026-10-14T00:00:01.000Z","targetCount":2,"callCount":4,"elapsedMs":1250}}\
        """);
  }

  @Test
  @DisplayName("호출 수 미측정은 JSON null로 구분한다")
  void unknownCallCountRendersAsNull() {
    // given
    var status = measuredStatus(OptionalLong.empty());
    // when
    var output = render.accessStatus(status).path("cost").toString();
    // then
    assertThat(output)
        .isEqualTo(
            """
        {"runId":"run-r1","recordedAt":"2026-10-14T00:00:01.000Z","targetCount":2,"callCount":null,"elapsedMs":1250}\
        """);
  }

  private AccessStatus measuredStatus(OptionalLong calls) {
    var at = Instant.parse("2026-10-14T00:00:01Z");
    return new AccessStatus(
        Optional.of(AccessResult.COMPLETE),
        Optional.of(at),
        0,
        0,
        0,
        0,
        0,
        Optional.empty(),
        false,
        0,
        Optional.of(at),
        0,
        Set.of(),
        Optional.of(new RunCost(new RunId("run-r1"), at, new AccessCost(2, calls, 1250))));
  }
}

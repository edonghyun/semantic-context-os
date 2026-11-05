package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.SourceQueries.AccessStatus;
import java.time.Instant;
import java.util.Optional;
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
            Set.of("file:F1"));
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
                    Set.of())))
        .isEqualTo(
            json.readTree(
                "{\"rawUnknown\":0,\"rawFailed\":0,\"documentsMissingAcl\":0,\"rawUnobserved\":0,\"rawStale\":0,\"consecutiveIncomplete\":0,\"documentsHidden\":0,\"aclUnreadable\":0,\"aclUnreadableFiles\":[]}"));
  }
}

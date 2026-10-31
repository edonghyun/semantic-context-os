package io.scos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 카탈로그 대조 게이트(logging.md §9·§10): 제품 main 소스의 {@code EventLog} 이벤트 집합과 §9 표가 이름·수준 모두 같다. 어긋나면 차이를
 * 실패 메시지에 싣는다 — 코드와 표를 같은 커밋에서 함께 고친다.
 */
@DisplayName("운영 코드의 이벤트와 logging.md §9 카탈로그는 같다")
class EventCatalogTest {
  static final String SAMPLE =
      """
      ## 2. 이벤트 이름

      | 키 | 뜻 |
      |---|---|
      | `status` | run 상태 |

      ## 9. 이벤트 카탈로그

      | 이벤트 | 수준 | 위치 | 소스·run | detail 키 | 테스트 |
      |---|---|---|---|---|---|
      | `collection.run.failed` | error | `scos-collection` `CollectionRuntime` | 소스·run | `status` | `T` |
      | `daemon.cycle.completed` | info / debug | `scos-server` `DaemonObserver` | — | `attempts` | `T` |

      `(+ error)`는 원인 예외를 뜻한다.

      ## 10. 새 이벤트를 더하는 절차

      | `not.an.event` | info |
      """;

  static EventLogCalls.Call call(String level, String name) {
    return new EventLogCalls.Call("A.java", 7, level, Optional.of(name), "\"" + name + "\"");
  }

  static Map<String, Set<String>> documentCatalog() throws IOException {
    return EventCatalog.parse(Files.readString(EventCatalog.DOCUMENT));
  }

  @Nested
  @DisplayName("게이트는")
  class Gate {
    @Test
    @DisplayName("코드와 카탈로그의 이벤트 이름 집합이 같다")
    void namesMatch() throws IOException {
      // when
      List<String> differences =
          EventCatalog.nameDifferences(EventNameRulesTest.productCalls(), documentCatalog());

      // then
      assertThat(differences).as("코드와 §9 표를 같은 커밋에서 맞춘다").isEmpty();
    }

    @Test
    @DisplayName("코드와 카탈로그의 이벤트 수준이 같다")
    void levelsMatch() throws IOException {
      // when
      List<String> differences =
          EventCatalog.levelDifferences(EventNameRulesTest.productCalls(), documentCatalog());

      // then
      assertThat(differences).as("코드와 §9 표를 같은 커밋에서 맞춘다").isEmpty();
    }
  }

  @Nested
  @DisplayName("카탈로그 읽기는")
  class Parse {
    @Test
    @DisplayName("§9 표의 행만 읽고 여러 수준을 나눈다")
    void readsSectionNineRows() {
      // when
      Map<String, Set<String>> catalog = EventCatalog.parse(SAMPLE);

      // then
      assertThat(catalog)
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "collection.run.failed", Set.of("error"),
                  "daemon.cycle.completed", Set.of("info", "debug")));
    }

    @Test
    @DisplayName("§9 표가 없으면 준비 실패로 멈춘다")
    void failsWithoutSectionNine() {
      // when·then
      assertThatThrownBy(() -> EventCatalog.parse("## 8. 파일\n\n본문\n"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("given 준비 실패");
    }
  }

  @Nested
  @DisplayName("대조는")
  class Compare {
    @Test
    @DisplayName("코드에만 있는 이름을 위치와 함께, 표에만 있는 이름을 보고한다")
    void reportsBothDirections() {
      // given
      List<EventLogCalls.Call> calls =
          List.of(call("error", "collection.run.failed"), call("warn", "lease.heartbeat.slow"));

      // when
      List<String> differences = EventCatalog.nameDifferences(calls, EventCatalog.parse(SAMPLE));

      // then
      assertThat(differences)
          .containsExactly(
              "카탈로그(§9)에 없다: lease.heartbeat.slow (A.java:7)", "코드에 없다: daemon.cycle.completed");
    }

    @Test
    @DisplayName("수준이 다른 이름을 두 쪽 수준과 함께 보고한다")
    void reportsLevelMismatch() {
      // given
      List<EventLogCalls.Call> calls =
          List.of(call("warn", "collection.run.failed"), call("info", "daemon.cycle.completed"));

      // when
      List<String> differences = EventCatalog.levelDifferences(calls, EventCatalog.parse(SAMPLE));

      // then
      assertThat(differences)
          .containsExactly(
              "수준이 다르다: collection.run.failed 코드 [warn] 카탈로그 [error]",
              "수준이 다르다: daemon.cycle.completed 코드 [info] 카탈로그 [debug, info]");
    }

    @Test
    @DisplayName("이름을 정할 수 없는 호출은 대조에서 빼고 이름 규칙에 맡긴다")
    void skipsUnresolvedCalls() {
      // given
      List<EventLogCalls.Call> calls =
          List.of(
              call("error", "collection.run.failed"),
              call("info", "daemon.cycle.completed"),
              call("debug", "daemon.cycle.completed"),
              new EventLogCalls.Call("A.java", 9, "info", Optional.empty(), "name"));

      // when
      List<String> differences = EventCatalog.nameDifferences(calls, EventCatalog.parse(SAMPLE));

      // then
      assertThat(differences).isEmpty();
    }
  }
}

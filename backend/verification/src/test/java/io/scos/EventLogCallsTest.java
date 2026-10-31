package io.scos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("EventLog 호출 찾기는 수준과 이벤트 이름을 소스에서 읽는다")
class EventLogCallsTest {
  @Test
  @DisplayName("문자열 리터럴 이름과 수준·줄을 읽는다")
  void readsLiteralNames() {
    // when
    List<EventLogCalls.Call> calls =
        EventLogCalls.of(
            "A.java",
            """
            class A {
              void run() {
                EventLog.warn(log, "lease.heartbeat.slow")
                    .log();
                EventLog
                    .error(
                        log,
                        "collection.run.failed").log();
              }
            }
            """);

    // then
    assertThat(calls)
        .containsExactly(
            new EventLogCalls.Call(
                "A.java",
                3,
                "warn",
                Optional.of("lease.heartbeat.slow"),
                "\"lease.heartbeat.slow\""),
            new EventLogCalls.Call(
                "A.java",
                5,
                "error",
                Optional.of("collection.run.failed"),
                "\"collection.run.failed\""));
  }

  @Test
  @DisplayName("같은 파일의 static final String 상수 이름을 풀어 읽는다")
  void resolvesConstants() {
    // when
    List<EventLogCalls.Call> calls =
        EventLogCalls.of(
            "A.java",
            """
            class A {
              private static final String CYCLE_EVENT = "daemon.cycle.completed";
              void run() {
                EventLog.debug(log, CYCLE_EVENT).log();
              }
            }
            """);

    // then
    assertThat(calls)
        .extracting(EventLogCalls.Call::name)
        .containsExactly(Optional.of("daemon.cycle.completed"));
  }

  @Test
  @DisplayName("리터럴도 같은 파일 상수도 아닌 이름은 원문만 남긴다")
  void leavesUnresolvedNames() {
    // when
    List<EventLogCalls.Call> calls =
        EventLogCalls.of(
            "A.java",
            """
            class A {
              void run(String name) {
                EventLog.info(log, "run." + name).log();
                EventLog.info(log, Names.OTHER).log();
              }
            }
            """);

    // then
    assertThat(calls)
        .extracting(EventLogCalls.Call::name, EventLogCalls.Call::argument)
        .containsExactly(
            tuple(Optional.empty(), "\"run.\" + name"), tuple(Optional.empty(), "Names.OTHER"));
  }

  @Test
  @DisplayName("주석과 문자열 안의 호출 모양은 건너뛴다")
  void skipsCommentsAndStrings() {
    // when
    List<EventLogCalls.Call> calls =
        EventLogCalls.of(
            "A.java",
            """
            class A {
              // EventLog.warn(log, "in.line.comment")
              /** {@code EventLog.error(log, "in.doc.comment")} */
              String s = "EventLog.info(log, \\"in.string.literal\\")";
            }
            """);

    // then
    assertThat(calls).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"io.scos.engine.support.logging.EventLog", "io.scos.engine.EventLog"})
  @DisplayName("EventLog 멤버의 static import를 줄과 함께 찾는다")
  void findsStaticImportsOfEventLog(String owner) {
    // when
    List<EventLogCalls.StaticImport> imports =
        EventLogCalls.staticImports(
            "A.java",
            """
            package io.scos.sample;

            import static %s.warn;
            import static %s.*;
            """
                .formatted(owner, owner));

    // then
    assertThat(imports)
        .containsExactly(
            new EventLogCalls.StaticImport("A.java", 3, "warn"),
            new EventLogCalls.StaticImport("A.java", 4, "*"));
  }

  @Test
  @DisplayName("다른 타입의 static import와 주석 안의 모양은 건너뛴다")
  void skipsOtherStaticImports() {
    // when
    List<EventLogCalls.StaticImport> imports =
        EventLogCalls.staticImports(
            "A.java",
            """
            import static java.util.Objects.requireNonNull;
            import static io.scos.engine.support.logging.EventLogTest.helper;
            import io.scos.engine.EventLog;
            // import static io.scos.engine.support.logging.EventLog.warn;
            """);

    // then
    assertThat(imports).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "collection.run.failed",
        "collection.snapshot.reconcile_guarded_repeatedly",
        "lease.heartbeat.slow",
        "v2.item.rejected"
      })
  @DisplayName("영역.대상.동작 소문자 점 표기는 형식에 맞다")
  void acceptsWellFormedNames(String name) {
    // when
    boolean wellFormed = EventLogCalls.isWellFormed(name);

    // then
    assertThat(wellFormed).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "collection.run",
        "collection.run.failed.again",
        "Collection.run.failed",
        "collection.runFailed.now",
        "collection.run-x.failed",
        "collection..failed",
        "collection.run.failed_",
        "collection._run.failed",
        "log"
      })
  @DisplayName("마디가 셋이 아니거나 대문자·하이픈·빈 단어가 있으면 형식이 아니다")
  void rejectsMalformedNames(String name) {
    // when
    boolean wellFormed = EventLogCalls.isWellFormed(name);

    // then
    assertThat(wellFormed).isFalse();
  }
}

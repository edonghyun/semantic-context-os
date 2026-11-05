package io.scos.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.scos.engine.support.logging.EventValues;
import io.scos.spi.source.SourceInstanceId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 이벤트 로그 작성기: 사람 갈래(텍스트) 메시지는 `이벤트이름 key=value …`다(logging.md §1.1). */
@DisplayName("이벤트 로그 메시지는 이름 뒤에 key=value를 쓴다(LG-1.1)")
class EventLogTest {
  final Logger logger = (Logger) LoggerFactory.getLogger(EventLogTest.class);
  final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attach() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  @Test
  @DisplayName("메시지는 이벤트 이름 뒤에 source·run·detail을 key=value로 넣은 순서대로 쓴다")
  void writesKeyValuesInOrder() {
    // when
    EventLog.warn(logger, "collection.item.rejected")
        .source(new SourceInstanceId("jira-main"))
        .run("run-7")
        .detail("failureKind", "unsupported_item", "elapsedMs", 1250, "ok", false)
        .log();

    // then
    assertThat(appender.list.getFirst().getFormattedMessage())
        .isEqualTo(
            "collection.item.rejected source=jira-main run=run-7 failureKind=unsupported_item elapsedMs=1250 ok=false");
  }

  @Test
  @DisplayName("detail 안의 객체는 펼쳐 안쪽 키로 쓰고 배열은 [a,b]로 쓴다")
  void flattensNestedObjects() {
    // when
    EventLog.error(logger, "collection.run.failed")
        .detail(
            "errorKind",
            "invalid_provider_response",
            "providerLocator",
            EventValues.detail(
                "list", "blocks:104f", "cursorTrail", List.of("-", "a1b2c3d4", "9f8e7d6c")))
        .log();

    // then
    assertThat(appender.list.getFirst().getFormattedMessage())
        .isEqualTo(
            "collection.run.failed errorKind=invalid_provider_response list=blocks:104f cursorTrail=[-,a1b2c3d4,9f8e7d6c]");
  }

  @Test
  @DisplayName("펼친 키가 같은 줄의 다른 키와 겹치면 바깥 키를 점으로 붙인다")
  void prefixesCollidingKeys() {
    // when
    EventLog.info(logger, "maintenance.sweep.completed")
        .detail("deleted", 1, "blob", EventValues.detail("deleted", 3, "scanned", 9))
        .log();

    // then
    assertThat(appender.list.getFirst().getFormattedMessage())
        .isEqualTo("maintenance.sweep.completed deleted=1 blob.deleted=3 scanned=9");
  }

  @Test
  @DisplayName("공백·=·따옴표·줄바꿈이 든 값과 빈 값은 JSON 문자열로 감싼다")
  void quotesAmbiguousValues() {
    // when
    EventLog.error(logger, "maintenance.sweep.failed")
        .detail(
            "errorSummary", "Notion API error: x",
            "query", "a=b",
            "quote", "say \"hi\"",
            "multi", "a\nb",
            "empty", "")
        .log();

    // then
    assertThat(appender.list.getFirst().getFormattedMessage())
        .isEqualTo(
            "maintenance.sweep.failed errorSummary=\"Notion API error: x\" query=\"a=b\" quote=\"say \\\"hi\\\"\" multi=\"a\\nb\" empty=\"\"");
  }

  @Test
  @DisplayName("detail 키-값은 짝이 맞아야 한다")
  void rejectsOddDetail() {
    // when·then
    assertThatThrownBy(() -> EventLog.info(logger, "collection.run.failed").detail("only-key"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

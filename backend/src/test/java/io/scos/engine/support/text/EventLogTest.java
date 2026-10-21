package io.scos.engine.support.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.scos.spi.connector.SourceInstanceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 이벤트 로그 작성기: 평문 프로필(명령·mcp)에서는 메시지가 이벤트 이름·소스·run·detail 요약이다. */
@DisplayName("이벤트 로그는 평문 프로필에서 이름·소스·run·detail 요약을 쓴다")
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
  @DisplayName("평문 메시지는 이벤트 이름 뒤에 소스·run·detail(JSON)을 붙인다")
  void summarizesForPlainText() {
    // when
    EventLog.warn(logger, "collection.item.rejected")
        .source(new SourceInstanceId("jira-main"))
        .run("run-7")
        .detail(EventLog.detail("failureKind", "unsupported_item"))
        .log();

    // then
    assertThat(appender.list.getFirst().getFormattedMessage())
        .isEqualTo(
            "collection.item.rejected source=jira-main run=run-7 {\"failureKind\":\"unsupported_item\"}");
  }

  @Test
  @DisplayName("detail 키-값은 짝이 맞아야 한다")
  void rejectsOddDetail() {
    // when·then
    assertThatThrownBy(() -> EventLog.detail("only-key"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

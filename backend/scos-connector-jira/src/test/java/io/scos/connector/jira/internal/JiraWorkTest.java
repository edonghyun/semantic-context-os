package io.scos.connector.jira.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 작업 값의 with…: 새 작업을 돌려주고, 붙인 순서대로 키가 쌓인다. */
@DisplayName("Jira 작업의 with…는 원래 작업을 바꾸지 않는다")
class JiraWorkTest {
  final ObjectMapper json = new ObjectMapper();

  @Test
  @DisplayName("with…는 원래 작업을 그대로 둔다")
  void withersLeaveOriginalUntouched() {
    // given
    JiraWork bare = JiraWork.of(json, WorkKind.ATTACHMENT);
    JiraWork withId = bare.withId("10001");

    // when
    withId.withKey("PROJ-1").withSize(json.getNodeFactory().numberNode(42));

    // then
    assertThat(bare.json()).hasToString("{\"kind\":\"attachment\"}");
    assertThat(withId.json()).hasToString("{\"kind\":\"attachment\",\"id\":\"10001\"}");
  }

  @Test
  @DisplayName("with…로 붙인 필드는 붙인 순서대로 쌓인다")
  void withersAppendInOrder() {
    // when
    JiraWork work =
        JiraWork.of(json, WorkKind.ATTACHMENT)
            .withId("10001")
            .withKey("PROJ-1")
            .withSize(json.getNodeFactory().numberNode(42));

    // then
    assertThat(work.json())
        .hasToString("{\"kind\":\"attachment\",\"id\":\"10001\",\"key\":\"PROJ-1\",\"size\":42}");
  }

  @Test
  @DisplayName("저장된 작업에 붙여도 키 순서와 모르는 키가 남는다")
  void witherKeepsStoredOrderAndUnknownKeys() {
    // given
    JiraWork stored =
        JiraWork.parse(json.readTree("{\"extra\":true,\"kind\":\"attachment\",\"id\":\"10001\"}"));

    // when
    JiraWork keyed = stored.withKey("PROJ-1");

    // then
    assertThat(keyed.json())
        .hasToString(
            "{\"extra\":true,\"kind\":\"attachment\",\"id\":\"10001\",\"key\":\"PROJ-1\"}");
    assertThat(stored.json())
        .hasToString("{\"extra\":true,\"kind\":\"attachment\",\"id\":\"10001\"}");
  }
}

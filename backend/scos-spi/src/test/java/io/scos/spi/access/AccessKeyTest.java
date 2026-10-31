package io.scos.spi.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.SourceInstanceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("접근 키는 종류·원천 인스턴스·값으로 식별한다(접근 설계 §3.1)")
class AccessKeyTest {
  @Test
  @DisplayName("키는 종류·인스턴스·값을 콜론으로 잇는다")
  void serializesIdentity() {
    // given
    AccessKey key = new AccessKey(AccessKey.Kind.USER, new SourceInstanceId("slack-a"), "U0123");
    // when
    String text = key.toString();
    // then
    assertThat(text).isEqualTo("user:slack-a:U0123");
  }

  @ParameterizedTest
  @CsvSource({
    "~project:10001, group:jira-a:~project%3A10001",
    "a%b:c+한글, group:jira-a:a%25b%3Ac+한글"
  })
  @DisplayName("값의 콜론과 퍼센트만 퍼센트 인코딩한다")
  void encodesValue(String value, String expected) {
    // given
    AccessKey key = new AccessKey(AccessKey.Kind.GROUP, new SourceInstanceId("jira-a"), value);
    // when
    String text = key.toString();
    // then
    assertThat(text).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "USER, slack-a, U0123, user:slack-a:U0123",
    "GROUP, jira-a, ~project:10001, group:jira-a:~project%3A10001",
    "GROUP, jira-a, %3A:+한글, group:jira-a:%253A%3A+한글",
    "DOMAIN, drive-a, example.com, domain:drive-a:example.com",
    "SCOPE, notion-a, *, scope:notion-a:*"
  })
  @DisplayName("parse는 toString의 역이다")
  void parsesSerializedKey(AccessKey.Kind kind, String instance, String value, String text) {
    // given
    AccessKey expected = new AccessKey(kind, new SourceInstanceId(instance), value);
    // when
    AccessKey parsed = AccessKey.parse(text);
    // then
    assertThat(parsed).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "user:slack-a:U0123",
        "group:jira-a:~project%3A10001",
        "group:jira-a:%253A%3A+한글",
        "domain:drive-a:example.com",
        "scope:notion-a:*",
        "user:slack-a:%252%3A5",
        "user:slack-a:%25%3A25"
      })
  @DisplayName("정규형 키는 파싱 후 직렬화해도 표기가 같다")
  void preservesCanonicalText(String text) {
    // when
    String serialized = AccessKey.parse(text).toString();
    // then
    assertThat(serialized).isEqualTo(text);
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(
      strings = {
        "unknown:slack-a:private@example.com",
        "user::private@example.com",
        "user:slack-a:",
        "user",
        "user:slack-a",
        "USER:slack-a:U1",
        "user:slack%25a:U1",
        "user:slack:a:U1",
        "user:slack-a:a%",
        "user:slack-a:a%20b",
        "user:slack-a:%2%3A5",
        "user:slack-a:%%3A25",
        "scope:notion-a:root"
      })
  @DisplayName("형식이 틀린 키는 입력을 노출하지 않는 예외로 거부한다 (I-1)")
  void rejectsMalformedKey(String text) {
    // when·then
    assertThatThrownBy(() -> AccessKey.parse(text))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid access key");
  }

  @ParameterizedTest
  @CsvSource(
      value = {"slack:a|U1", "slack%a|U1", "slack-a|", "slack-a|''"},
      delimiter = '|')
  @DisplayName("직접 만든 키도 인스턴스 구분자와 빈 값을 거부한다")
  void rejectsInvalidComponents(String instance, String value) {
    // when·then
    assertThatThrownBy(
            () -> new AccessKey(AccessKey.Kind.USER, new SourceInstanceId(instance), value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid access key");
  }

  @Test
  @DisplayName("같은 종류의 두 인스턴스 키는 다르다")
  void distinguishesInstances() {
    // given
    AccessKey first = AccessKey.parse("user:slack-a:U1");
    // when
    AccessKey second = AccessKey.parse("user:slack-b:U1");
    // then
    assertThat(second).isNotEqualTo(first);
  }
}

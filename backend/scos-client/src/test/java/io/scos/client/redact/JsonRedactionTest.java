package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 줄 가림: 트리의 문자열 값·필드 이름에 평문 규칙을 적용하고, 비밀 키 필드는 값을 통째로 가리며, 문자열 속 JSON 층에 들어간다. 바뀐 것이 없으면 원문
 * 그대로다.
 */
@DisplayName("JSON 줄 가림은 문자열 값을 트리로 찾아 가리고 바뀐 것이 없으면 원문을 돌려준다")
class JsonRedactionTest {
  private static final long FUZZ_SEED = 20261029L;
  private static final int FUZZ_LINES = 2_000;
  private static final int MAX_DEPTH = 3;

  /** 개인 키 본문 줄 수 — 줄마다 34자라 약 4KB다. */
  private static final int PEM_LINES = 128;

  /** 직렬화 전 원문 조각 — 따옴표·역슬래시·줄바꿈이 그대로 들어 있어 감쌀 때마다 이스케이프가 겹친다. */
  private static final List<String> FUZZ_PIECES =
      List.of(
          "PASSWORD=\"",
          "pass=\"",
          "\"",
          "\\\"",
          "\\",
          "\n",
          "mysql -h \"my host\" -p",
          "redis-cli -h \"my host\" -a ",
          "docker login -u \"a b\" -p ",
          "{\"api_key\":\"",
          "{\"password\": \"",
          "\"}",
          "TOKEN=\"",
          "api_key='say \"hi\" ",
          "curl -u \"us er:",
          "curl -u user:\"",
          "{\"Authorization\":\"Basic ",
          "-----BEGIN PRIVATE KEY-----",
          "hooks.slack.com/workflows/a-b");

  private final ObjectMapper json = JsonMapper.builder().build();
  private final JsonRedaction redaction = JsonRedaction.standard();

  private String apply(String line) {
    return redaction.apply(line, redaction.read(line));
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("키가 겹친 줄은 트리가 원문 전체를 대표하지 않으므로 읽기를 거절한다")
  @ValueSource(
      strings = {
        "{\"type\":\"user\",\"m\":\"sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA\",\"m\":\"hi\"}",
        "{\"type\":\"user\",\"password\":\"Zq9xW7vK2mP4\",\"password\":\"\"}",
        "{\"a\":{\"m\":\"x\",\"m\":\"y\"}}"
      })
  void refusesToReadDuplicateKeys(String line) {
    // when
    Throwable thrown = catchThrowable(() -> redaction.read(line));

    // then
    assertThat(thrown).isInstanceOf(JacksonException.class);
  }

  @Test
  @DisplayName("가릴 것이 없는 줄은 공백·이스케이프까지 원문 그대로다")
  void returnsUnchangedLineByteIdentical() {
    // given
    String line = "{ \"type\" : \"user\", \"text\":\"caf\\u00e9 \\/ ok\", \"n\" : 1.50 }";

    // when
    String out = apply(line);

    // then
    assertThat(out).isSameAs(line);
  }

  @Test
  @DisplayName("일반 필드의 Basic 문구는 Authorization 헤더로 가리지 않는다")
  void leavesBasicTextOutsideAuthorizationHeader() {
    // given
    String line = "{\"text\":\"Basic QWxwaGE6cHVibGlj\"}";
    // when
    String out = apply(line);
    // then
    assertThat(out).isEqualTo("{\"text\":\"Basic QWxwaGE6cHVibGlj\"}");
  }

  @Test
  @DisplayName("중첩 객체·배열의 문자열 값을 가린다")
  void masksNestedStringValues() {
    // when
    String out =
        apply(
            "{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"use sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA now\"}]}}");

    // then
    assertThat(out)
        .isEqualTo(
            "{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"use [REDACTED] now\"}]}}");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("JSON으로 읽히는 문자열 값은 풀어서 가리고 다시 직렬화한다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "{\"stdout\":\"{\\\"cmd\\\":\\\"password=hunter2 x\\\"}\"} → {\"stdout\":\"{\\\"cmd\\\":\\\"password=[REDACTED] x\\\"}\"}",
        "{\"stdout\":\"[\\\"token=abc123def\\\"]\"} → {\"stdout\":\"[\\\"token=[REDACTED]\\\"]\"}",
        "{\"toolUseResult\":{\"stdout\":\"{\\\"pass\\\": \\\"hunter2pw\\\"}\"}} → {\"toolUseResult\":{\"stdout\":\"{\\\"pass\\\":\\\"[REDACTED]\\\"}\"}}",
        "{\"stdout\":\"{\\\"command\\\":\\\"PASSWORD=\\\\\\\"S 2 pw\\\\\\\"\\\"}\"} → {\"stdout\":\"{\\\"command\\\":\\\"PASSWORD=\\\\\\\"[REDACTED]\\\\\\\"\\\"}\"}",
        "{\"c\":\"{\\\"k\\\":\\\"a\\\\nAKIASYNTHETICEXAMPLE\\\"}\"} → {\"c\":\"{\\\"k\\\":\\\"a\\\\n[REDACTED]\\\"}\"}"
      })
  void masksInsideJsonInString(String line, String expected) {
    // when
    String out = apply(line);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @Test
  @DisplayName("바뀐 것이 없는 문자열 속 JSON은 원래 글자 그대로 남긴다")
  void keepsUnchangedJsonInStringVerbatim() {
    // when
    String out = apply("{\"stdout\":\"{\\\"a\\\": 1}\",\"t\":\"password=x1\"}");

    // then
    assertThat(out).isEqualTo("{\"stdout\":\"{\\\"a\\\": 1}\",\"t\":\"password=[REDACTED]\"}");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("키가 겹친 문자열 속 JSON은 트리로 들어가지 않고 평문으로 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "{\"t\":\"{\\\"m\\\":\\\"sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA\\\",\\\"m\\\":\\\"hi\\\"}\"} → {\"t\":\"{\\\"m\\\":\\\"[REDACTED]\\\",\\\"m\\\":\\\"hi\\\"}\"}",
        "{\"t\":\"{\\\"password\\\":\\\"Zq9xW7vK2mP4\\\",\\\"password\\\":\\\"\\\"}\"} → {\"t\":\"{\\\"password\\\":\\\"[REDACTED]\\\",\\\"password\\\":\\\"\\\"}\"}"
      })
  void masksDuplicateKeyJsonInStringAsPlainText(String line, String expected) {
    // when
    String out = apply(line);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @Test
  @DisplayName("JSON 뒤에 글자가 이어진 문자열은 평문으로 가린다")
  void masksJsonFollowedByTextAsPlainText() {
    // when
    String out = apply("{\"t\":\"{\\\"password\\\": \\\"hunter2\\\"} tail\"}");

    // then
    assertThat(out).isEqualTo("{\"t\":\"{\\\"password\\\": \\\"[REDACTED]\\\"} tail\"}");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("비밀 키 필드의 문자열 값은 통째로 가리고 필드 이름은 남긴다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "{\"password\":\"hunter2\"} → {\"password\":\"[REDACTED]\"}",
        "{\"apiKey\": \"S 2 pw\"} → {\"apiKey\":\"[REDACTED]\"}",
        "{\"token\":\"abc\"} → {\"token\":\"[REDACTED]\"}",
        "{\"client_secret\":\"hunter2\"} → {\"client_secret\":\"[REDACTED]\"}",
        "{\"private_key_id\":\"abc\"} → {\"private_key_id\":\"[REDACTED]\"}",
        "{\"auth_token\":\"hunter2\"} → {\"auth_token\":\"[REDACTED]\"}",
        "{\"X-Api-Key\":\"SECRETX61\"} → {\"X-Api-Key\":\"[REDACTED]\"}",
        "{\"X-Auth-Token\":\"SECRETX62\"} → {\"X-Auth-Token\":\"[REDACTED]\"}",
        "{\"db_pass\":\"hunter2pw\"} → {\"db_pass\":\"[REDACTED]\"}",
        "{\"pass\":\"hunter2pw\"} → {\"pass\":\"[REDACTED]\"}",
        "{\"DB_PWD\": \"hunter2pw\"} → {\"DB_PWD\":\"[REDACTED]\"}",
        "{\"password\":\"abc\\ndefghi\"} → {\"password\":\"[REDACTED]\"}",
        "{\"api_key\":\"abc\\\"def SECRETX32\"} → {\"api_key\":\"[REDACTED]\"}"
      })
  void masksSecretFieldValue(String line, String expected) {
    // when
    String out = apply(line);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("점으로 이은 비밀 필드 이름과 이름 둘레의 공백도 인식한다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "{\"spring.datasource.password\":\"SECRET\"} → {\"spring.datasource.password\":\"[REDACTED]\"}",
        "{\"password.value\":\"SECRET\"} → {\"password.value\":\"[REDACTED]\"}",
        "{\"Password \":\"SECRET\"} → {\"Password \":\"[REDACTED]\"}",
        "{\"database password\":\"SECRET\"} → {\"database password\":\"[REDACTED]\"}",
        "{\"spring.datasource.password.value\":\"SECRET\"} → {\"spring.datasource.password.value\":\"[REDACTED]\"}"
      })
  void masksQualifiedFieldValue(String line, String expected) {
    // when
    String out = apply(line);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("비슷한 키·빈 값·경로 pwd·숫자 값 필드는 원문 그대로다")
  @ValueSource(
      strings = {
        "{\"passed\":3,\"failed\":0}",
        "{\"passed\":\"yes please\"}",
        "{\"pass_rate\":\"0.95\"}",
        "{\"bypass\":\"a b\",\"compass\":\"north\"}",
        "{\"pass\":3}",
        "{\"password\":\"\"}",
        "{\"pwd\":\"/srv/project\"}",
        "{\"usage\":{\"input_tokens\":12,\"output_tokens\":3,\"max_tokens\":5}}"
      })
  void leavesLookalikeFields(String line) {
    // when
    String out = apply(line);

    // then
    assertThat(out).isSameAs(line);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("Authorization 필드의 값은 방식 이름을 남기고 자격 증명을 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "{\"headers\":{\"Authorization\":\"Basic dXNlcjpwYXNz\"}} → {\"headers\":{\"Authorization\":\"Basic [REDACTED]\"}}",
        "{\"authorization\":\"Token SECRETX58abcdef\"} → {\"authorization\":\"Token [REDACTED]\"}",
        "{\"Authorization\":\"Bearer SECRETX59abcdefghijklmnopqrstuv\"} → {\"Authorization\":\"Bearer [REDACTED]\"}"
      })
  void masksAuthorizationField(String line, String expected) {
    // when
    String out = apply(line);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("한 문자열 값의 비밀은 다음 필드로 넘어가지 않는다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "{\"a\":\"-----BEGIN RSA PRIVATE KEY-----\",\"b\":{\"c\":\"-----END RSA PRIVATE KEY-----\"}} → {\"a\":\"[REDACTED]\",\"b\":{\"c\":\"-----END RSA PRIVATE KEY-----\"}}",
        "{\"command\":\"echo password='abc def\",\"after\":\"ok\"} → {\"command\":\"echo password='[REDACTED]\",\"after\":\"ok\"}",
        "{\"a\":\"password='x \\\"y\\\" z\",\"keep\":\"visible\"} → {\"a\":\"password='[REDACTED]\",\"keep\":\"visible\"}",
        "{\"t\":\"password=\",\"u\":\"x\"} → {\"t\":\"password=\",\"u\":\"x\"}"
      })
  void keepsSecretInsideItsString(String line, String expected) {
    // when
    String out = apply(line);

    // then
    assertThat(out).isEqualTo(expected);
  }

  @Test
  @DisplayName("몇 KB 개인 키 블록도 끝까지 가리고 이웃 필드는 남긴다")
  void masksLargePrivateKeyAndKeepsNeighbour() {
    // given
    String body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC\\n".repeat(PEM_LINES);
    String line =
        "{\"t\":\"-----BEGIN PRIVATE KEY-----\\n" + body + "-----END PRIVATE KEY-----\",\"u\":1}";

    // when
    String out = apply(line);

    // then
    assertThat(out).isEqualTo("{\"t\":\"[REDACTED]\",\"u\":1}");
  }

  @Test
  @DisplayName("필드 이름에 든 비밀도 가린다")
  void masksFieldNames() {
    // when
    String out = apply("{\"sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA\":1}");

    // then
    assertThat(out).isEqualTo("{\"[REDACTED]\":1}");
  }

  @Test
  @DisplayName("비밀 조각을 이어 붙여 몇 겹 감싼 줄도 가린 결과는 JSON이다")
  void outputIsAlwaysJson() {
    // given
    var random = new SplittableRandom(FUZZ_SEED);
    List<String> lines = IntStream.range(0, FUZZ_LINES).mapToObj(i -> fuzzLine(random)).toList();

    // when
    List<String> invalid = lines.stream().map(this::apply).filter(out -> !isJson(out)).toList();

    // then
    assertThat(invalid).isEmpty();
  }

  /** 조각 1~10개를 이어 붙여 `{"k": …}`로 1~3번 감싼 진짜 JSON 줄. */
  private String fuzzLine(SplittableRandom random) {
    var text = new StringBuilder();
    int count = 1 + random.nextInt(10);
    for (int i = 0; i < count; i++) {
      text.append(FUZZ_PIECES.get(random.nextInt(FUZZ_PIECES.size())));
    }
    String line = text.toString();
    int depth = 1 + random.nextInt(MAX_DEPTH);
    for (int i = 0; i < depth; i++) {
      line = json.writeValueAsString(Map.of("k", line));
    }
    return line;
  }

  private boolean isJson(String text) {
    try {
      json.readTree(text);
      return true;
    } catch (JacksonException e) {
      return false;
    }
  }
}

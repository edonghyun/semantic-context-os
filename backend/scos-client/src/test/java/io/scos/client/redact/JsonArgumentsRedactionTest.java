package io.scos.client.redact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("JSON 문자열 배열은 인자 경계를 유지하며 명령·옵션의 비밀을 가린다")
class JsonArgumentsRedactionTest {
  private final JsonRedaction redaction = JsonRedaction.standard();

  @ParameterizedTest(name = "{0}")
  @DisplayName("이웃 인자와 명령 이름으로 비밀인 값을 알아내 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "{\"args\":[\"--password\",\"SECRET\"]} → {\"args\":[\"--password\",\"[REDACTED]\"]}",
        "{\"command\":[\"mysql\",\"-uroot\",\"-pSECRET\"]} → {\"command\":[\"mysql\",\"-uroot\",\"-p[REDACTED]\"]}",
        "[\"redis-cli\",\"-a\",\"SECRET\"] → [\"redis-cli\",\"-a\",\"[REDACTED]\"]",
        "[\"docker\",\"login\",\"-u\",\"root\",\"-p\",\"SECRET\"] → [\"docker\",\"login\",\"-u\",\"root\",\"-p\",\"[REDACTED]\"]",
        "[\"az\",\"login\",\"-p\",\"SECRET\"] → [\"az\",\"login\",\"-p\",\"[REDACTED]\"]",
        "[\"AZ\",\"login\",\"-p\",\"SECRET\"] → [\"AZ\",\"login\",\"-p\",\"[REDACTED]\"]",
        "[\"sshpass\",\"-p\",\"SECRET\",\"ssh\",\"host\"] → [\"sshpass\",\"-p\",\"[REDACTED]\",\"ssh\",\"host\"]",
        "[\"curl\",\"--user\",\"admin:SECRET\"] → [\"curl\",\"--user\",\"admin:[REDACTED]\"]",
        "[\"--password\",\"S ECRET\",\"--token\",\"OTHER SECRET\"] → [\"--password\",\"[REDACTED]\",\"--token\",\"[REDACTED]\"]",
        "[\"--password\",\"S\\\"ECRET\\tTAIL\\nEND\"] → [\"--password\",\"[REDACTED]\"]",
        "[\"mysql\",\"-e\",\"say 'hello\",\"-pS ECRET\"] → [\"mysql\",\"-e\",\"say 'hello\",\"-p[REDACTED]\"]",
        "{\"stdout\":\"[\\\"mysql\\\",\\\"-pSECRET\\\"]\"} → {\"stdout\":\"[\\\"mysql\\\",\\\"-p[REDACTED]\\\"]\"}",
        "[0,[\"--password\",\"SECRET\"]] → [0,[\"--password\",\"[REDACTED]\"]]"
      })
  void masksArgumentValue(String line, String expected) {
    // when
    String out = redaction.apply(line, redaction.read(line));

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("원소만으로 가린 비밀의 뒷문맥은 배열에서도 남긴다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "[\"git\",\"clone\",\"https://user:SECRET@github.com/x\"] → [\"git\",\"clone\",\"https://user:[REDACTED]@github.com/x\"]",
        "[\"psql\",\"postgres://u:SECRET@db:5432/app\",\"-c\",\"select 1\"] → [\"psql\",\"postgres://u:[REDACTED]@db:5432/app\",\"-c\",\"select 1\"]",
        "{\"urls\":[\"https://u:SECRET@h.example.com/x\"]} → {\"urls\":[\"https://u:[REDACTED]@h.example.com/x\"]}",
        "{\"env\":[\"DB_URL=postgres://u:SECRET@db/app\"]} → {\"env\":[\"DB_URL=postgres://u:[REDACTED]@db/app\"]}",
        "[\"config password=SECRET other=1 and more text here\"] → [\"config password=[REDACTED] other=1 and more text here\"]",
        "[\"export API_KEY=SECRET; run job\"] → [\"export API_KEY=[REDACTED] run job\"]",
        "{\"lines\":[\"a\",\"DB password: SECRET then the rest of the line\"]} → {\"lines\":[\"a\",\"DB password: [REDACTED] then the rest of the line\"]}",
        "[\"password='SECRET' trailing text\"] → [\"password='[REDACTED]' trailing text\"]",
        "[\"password=\\\"SECRET\\\" trailing text\"] → [\"password=\\\"[REDACTED]\\\" trailing text\"]"
      })
  void preservesIndividuallyRedactedContext(String line, String expected) {
    // when
    String out = redaction.apply(line, redaction.read(line));

    // then
    assertThat(out).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("옵션이 원소 전체를 비밀로 정하면 기존 가림 밖의 값도 가린다")
  @CsvSource(
      delimiterString = " → ",
      quoteCharacter = '`',
      value = {
        "[\"--password\",\"https://u:SECRET@host/path\"] → [\"--password\",\"[REDACTED]\"]",
        "[\"--password\",\"config password=SECRET trailing text\"] → [\"--password\",\"[REDACTED]\"]",
        "[\"--password\",\"[REDACTED] trailing text\"] → [\"--password\",\"[REDACTED]\"]"
      })
  void masksNewContextBeyondExistingMask(String line, String expected) {
    // when
    String out = redaction.apply(line, redaction.read(line));

    // then
    assertThat(out).isEqualTo(expected);
  }

  @Test
  @DisplayName("문장 배열도 비밀번호 키 다음 원소를 보수적으로 값으로 가린다")
  void masksSentenceAfterPasswordPrompt() {
    // given
    String line = "[\"Enter your password:\",\"Then click next to continue\"]";

    // when
    String out = redaction.apply(line, redaction.read(line));

    // then
    assertThat(out).isEqualTo("[\"Enter your password:\",\"[REDACTED]\"]");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("문맥에서 비밀이 아닌 인자 배열은 원문 그대로 둔다")
  @ValueSource(
      strings = {
        "[]",
        "[\"\",\"hello world\"]",
        "[\"--password\",\"\",\"visible\"]",
        "[\"--password-stdin\",\"visible\"]",
        "[\"--password\",\"--verbose\"]",
        "[\"mysql\",\"-p\",\"database\"]",
        "[\"mysql\",\"-p3306:3306\"]",
        "[\"ssh\",\"-p\",\"22\"]",
        "[\"curl\",\"--user\",\"99:99\"]",
        "[\"docker\",\"run\",\"--name\",\"mysql\",\"-p3306:3306\"]"
      })
  void leavesOrdinaryArguments(String line) {
    // when
    String out = redaction.apply(line, redaction.read(line));

    // then
    assertThat(out).isSameAs(line);
  }

  @Test
  @DisplayName("원소에 나뉜 비밀 마커는 일부 원문을 복원하지 않고 가린다")
  void masksSecretAcrossArgumentBoundaries() {
    // given
    String line = "[\"-----BEGIN\",\"PRIVATE\",\"KEY-----\"]";

    // when
    String out = redaction.apply(line, redaction.read(line));

    // then
    assertThat(out).isEqualTo("[\"[REDACTED]\",\"[REDACTED]\",\"[REDACTED]\"]");
  }

  @Test
  @DisplayName("숫자·객체 원소가 있는 배열도 가릴 것이 없으면 원문 그대로다")
  void keepsUnchangedMixedArrayVerbatim() {
    // given
    String line = "[ 1, {\"message\" : \"hello\"} ]";

    // when
    String out = redaction.apply(line, redaction.read(line));

    // then
    assertThat(out).isSameAs(line);
  }
}

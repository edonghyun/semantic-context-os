package io.scos.harness.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testcontainers 이미지는 태그 + digest로 고정하고(TESTING §6), 하네스와 backend가 같은 참조를 쓴다. backend 소스는 Gradle이
 * test 태스크의 입력으로 선언하고 절대 경로를 넘긴다 — backend만 바꾼 커밋에서도 이 테스트가 다시 돈다.
 */
@DisplayName("Postgres 이미지는 digest로 고정하고 backend와 같은 참조를 쓴다(TESTING §6)")
class PostgresImageTest {
  /** backend `PostgresSupport.IMAGE`의 `DockerImageName.parse("…")` 리터럴. */
  static final Pattern PARSED_LITERAL =
      Pattern.compile("DockerImageName\\.parse\\(\\s*\"([^\"]+)\"");

  static String backendImageReference() throws IOException {
    String path = System.getProperty("harness.backend.postgresSupport");
    if (path == null) {
      throw new IllegalStateException(
          "given 준비 실패: harness.backend.postgresSupport 미설정 — Gradle test 태스크로 실행한다");
    }
    String source = Files.readString(Path.of(path));
    Matcher literal = PARSED_LITERAL.matcher(source);
    if (!literal.find()) {
      throw new IllegalStateException("given 준비 실패: DockerImageName.parse(\"…\") 리터럴이 없다: " + path);
    }
    return literal.group(1);
  }

  @Test
  @DisplayName("이미지는 pg17 태그에 sha256 digest를 붙여 고정한다")
  void pinsTagWithDigest() {
    // when·then
    assertThat(PostgresImage.REFERENCE).matches("pgvector/pgvector:pg17@sha256:[0-9a-f]{64}");
  }

  @Test
  @DisplayName("backend PostgresSupport도 같은 이미지 참조를 쓴다")
  void backendUsesSameReference() throws Exception {
    // when
    String backend = backendImageReference();

    // then
    assertThat(backend).isEqualTo(PostgresImage.REFERENCE);
  }
}

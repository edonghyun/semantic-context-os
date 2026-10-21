package io.scos.server.scripts;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.server.DatabaseVersionCheck;
import io.scos.server.OpsConfiguration.DatabaseProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PostgreSQL 이미지 digest와 기대 버전이 흩어진 곳에서 같은 값이다(F10 리뷰 A m6). digest: backend
 * 테스트(PostgresSupport)·공개 데모 compose(harness PostgresImage는 PostgresImageTest가 backend와 묶는다. 사내 배포
 * 문서는 OperationsDocPinTest). 기대 버전: Expected.DEFAULT·application.yml 기본값·
 * DatabaseProperties @DefaultValue. 이미지와 기대 버전의 실제 일치는 DatabaseVersionCheckPostgresTest가 본다.
 */
@DisplayName("PostgreSQL 이미지 digest와 기대 버전은 모든 곳에서 같은 값이다")
class PostgresVersionPinTest {
  static final Path BACKEND = Path.of(".").toAbsolutePath().normalize();
  static final Pattern DIGEST = Pattern.compile("pgvector/pgvector:pg17@sha256:[0-9a-f]{64}");

  static String read(String relative) {
    try {
      return Files.readString(BACKEND.resolve(relative));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Set<String> digests(String text) {
    Set<String> found = new TreeSet<>();
    Matcher m = DIGEST.matcher(text);
    while (m.find()) found.add(m.group());
    return found;
  }

  static String ymlDefault(String variable) {
    Matcher m =
        Pattern.compile("\\$\\{" + variable + ":([^}]+)}")
            .matcher(read("src/main/resources/application.yml"));
    if (!m.find())
      throw new IllegalStateException("given 준비 실패: application.yml에 " + variable + " 기본값이 없다");
    return m.group(1);
  }

  static String propertyDefault(String component) throws Exception {
    // @DefaultValue는 레코드 생성자 매개변수에 붙는다 — 컴포넌트 이름으로 정규 생성자의 매개변수를 찾는다
    RecordComponent[] components = DatabaseProperties.class.getRecordComponents();
    Class<?>[] types =
        Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
    Parameter[] parameters = DatabaseProperties.class.getDeclaredConstructor(types).getParameters();
    for (int i = 0; i < components.length; i++)
      if (components[i].getName().equals(component))
        return parameters[i].getAnnotation(DefaultValue.class).value()[0];
    throw new IllegalStateException("given 준비 실패: DatabaseProperties에 " + component + "가 없다");
  }

  /** 기준 digest: backend 테스트(PostgresSupport)에 정확히 하나 있는 값. */
  static String pinnedDigest() {
    Set<String> pinned =
        digests(read("src/test/java/io/scos/collection/internal/postgres/PostgresSupport.java"));
    if (pinned.size() != 1)
      throw new IllegalStateException(
          "given 준비 실패: PostgresSupport의 이미지 digest가 하나가 아니다 — " + pinned);
    return pinned.iterator().next();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"../demo/compose.yaml"})
  @DisplayName("데모의 이미지 digest는 테스트와 같은 값 하나뿐이다")
  void sameDigestEverywhere(String file) {
    // given
    String pinned = pinnedDigest();

    // when
    Set<String> found = digests(read(file));

    // then
    assertThat(found).containsExactly(pinned);
  }

  @Test
  @DisplayName("기대 서버 버전은 코드·yml·속성 기본값 모두 17.11이다")
  void sameExpectedServerVersion() throws Exception {
    // when
    List<String> pins =
        List.of(
            DatabaseVersionCheck.Expected.DEFAULT.server(),
            ymlDefault("SCOS_DB_EXPECTED_SERVER_VERSION"),
            propertyDefault("expectedServerVersion"));

    // then
    assertThat(pins).containsExactly("17.11", "17.11", "17.11");
  }

  @Test
  @DisplayName("기대 pgvector 버전은 코드·yml·속성 기본값 모두 0.8.7이다")
  void sameExpectedVectorVersion() throws Exception {
    // when
    List<String> pins =
        List.of(
            DatabaseVersionCheck.Expected.DEFAULT.vector(),
            ymlDefault("SCOS_DB_EXPECTED_VECTOR_VERSION"),
            propertyDefault("expectedVectorVersion"));

    // then
    assertThat(pins).containsExactly("0.8.7", "0.8.7", "0.8.7");
  }
}

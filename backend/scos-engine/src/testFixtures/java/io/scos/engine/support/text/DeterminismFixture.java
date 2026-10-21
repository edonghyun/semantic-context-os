package io.scos.engine.support.text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Named;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 언어 중립 결정론 fixture(harness/fixtures/p1/determinism.json) 로더 — 모듈별 결정론 테스트가 공유한다. */
public final class DeterminismFixture {
  private DeterminismFixture() {}

  public static JsonNode load() {
    try {
      Path file =
          Path.of(
              System.getProperty("scos.fixtures.dir", "../harness/fixtures/p1"),
              "determinism.json");
      return new ObjectMapper().readTree(Files.readString(file));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** fixture 배열 → 파라미터화 사례(이름 = {@code nameField} 값). 사례가 0개면 공허한 통과가 되므로 예외로 실패시킨다(TESTING §8). */
  public static Stream<Named<JsonNode>> cases(JsonNode array, String nameField) {
    if (array == null || !array.isArray() || array.isEmpty())
      throw new IllegalStateException("fixture 사례가 없다: " + nameField);
    return StreamSupport.stream(array.spliterator(), false)
        .map(c -> Named.of(c.get(nameField).asString(), c));
  }
}

package io.scos.connector.notion.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** harness/fixtures/notion(TS make-notion-fixture.ts 생성)을 읽는다. 경로는 Gradle이 시스템 속성으로 넘긴다. */
public final class NotionFixtures {
  public static final ObjectMapper JSON = new ObjectMapper();
  static final Path DIR =
      Path.of(System.getProperty("scos.fixtures.notion.dir", "../harness/fixtures/notion"));

  private NotionFixtures() {}

  static JsonNode read(String name) {
    try {
      return JSON.readTree(Files.readString(DIR.resolve(name)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 가짜 Notion 데이터셋(notion-dataset.json). */
  public static JsonNode dataset() {
    return read("notion-dataset.json");
  }

  /** TS 클라이언트·응답 검증·설정·체크포인트 표(expected-client.json). */
  static JsonNode client() {
    return read("expected-client.json");
  }

  /** 데이터셋의 소스 설정 하나(configs.<name>)를 JSON 원문으로. */
  public static String config(String name) {
    return dataset().get("configs").get(name).get("value").toString();
  }
}

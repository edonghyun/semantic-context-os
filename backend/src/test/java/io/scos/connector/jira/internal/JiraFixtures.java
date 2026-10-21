package io.scos.connector.jira.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** harness/fixtures/jira(TS make-jira-fixture.ts 생성)를 읽는다. 경로는 Gradle이 시스템 속성으로 넘긴다. */
public final class JiraFixtures {
  public static final ObjectMapper JSON = new ObjectMapper();
  public static final Path DIR =
      Path.of(System.getProperty("scos.fixtures.jira.dir", "../harness/fixtures/jira"));

  /**
   * 데이터셋 PNG 첨부(50002) 바이트의 sha256 — TS가 낸 expected-raw-items.json jsonBlobs의 값을 옮겨 적었다(제품 해시 함수로
   * 계산하지 않는다).
   */
  static final String PNG_SHA256 =
      "02a3e298f1533f62558c58e4c70edcab9af5a50d62d925fd5390942020fb0fb8";

  private JiraFixtures() {}

  static JsonNode read(String name) {
    try {
      return JSON.readTree(Files.readString(DIR.resolve(name)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** TS 클라이언트·설정 표(expected-client.json). */
  static JsonNode client() {
    return read("expected-client.json");
  }

  /** 데이터셋이 이 첨부 원본 경로로 주는 바이트. */
  static byte[] attachmentBytes(String id) {
    for (JsonNode r : dataset().get("routes"))
      if (r.get("path").asString().equals("/rest/api/3/attachment/content/" + id)) {
        JsonNode reply = r.get("responses").get(0);
        return reply.has("base64")
            ? Base64.getDecoder().decode(reply.get("base64").asString())
            : reply.get("text").asString().getBytes(StandardCharsets.UTF_8);
      }
    throw new IllegalStateException("데이터셋에 첨부 " + id + "가 없다");
  }

  /** 가짜 Jira 데이터셋(jira-dataset.json). */
  public static JsonNode dataset() {
    return read("jira-dataset.json");
  }
}

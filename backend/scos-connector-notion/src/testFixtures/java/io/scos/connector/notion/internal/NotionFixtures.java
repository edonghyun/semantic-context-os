package io.scos.connector.notion.internal;

import io.scos.connector.notion.internal.NotionConfiguration.Roots;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** harness/fixtures/notion(TS make-notion-fixture.ts 생성)을 읽는다. 경로는 Gradle이 시스템 속성으로 넘긴다. */
public final class NotionFixtures {
  static final String UNKNOWN_PAGE = "unknown";
  static final String CHILD_PAGE = "child";
  static final String DATA_SOURCE = "ds";
  public static final ObjectMapper JSON = new ObjectMapper();
  static final Path DIR =
      Path.of(System.getProperty("scos.fixtures.notion.dir", "../harness/fixtures/notion"));

  private NotionFixtures() {}

  static Roots roots(String... pageIds) {
    return new Roots(
        List.of(pageIds), false, Roots.DEFAULT_INCREMENTAL_INTERVAL, Roots.DEFAULT_OVERLAP);
  }

  static JsonNode read(String name) {
    try {
      return JSON.readTree(Files.readString(DIR.resolve(name)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** TS 원본 데이터셋에 v4 정렬 query·행 단건 조회 각본을 결합한다. */
  public static JsonNode dataset() {
    var dataset = read("notion-dataset.json");
    var reconciliation = read("notion-reconciliation-routes.json");
    for (String phase : List.of("routes", "afterEdit", "afterShare"))
      ((ArrayNode) dataset.path(phase)).addAll((ArrayNode) reconciliation.path(phase));
    return dataset;
  }

  static JsonNode reconciliationRequest(JsonNode request) {
    var current = (ObjectNode) request.deepCopy();
    if (!current.path("path").asString().endsWith("/query")) return current;
    boolean recordedBody = current.path("body").isString();
    var body =
        recordedBody
            ? (ObjectNode) JSON.readTree(current.path("body").asString())
            : (ObjectNode) current.path("body");
    body.putArray("sorts")
        .addObject()
        .put("timestamp", "created_time")
        .put("direction", "ascending");
    if (recordedBody) current.put("body", body.toString());
    else current.set("body", body);
    return current;
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

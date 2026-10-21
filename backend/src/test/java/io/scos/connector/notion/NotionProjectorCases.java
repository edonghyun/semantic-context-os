package io.scos.connector.notion;

import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.projection.Projector.ContentReader;
import io.scos.spi.projection.SourceDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TS NotionProjector 경계 표(expected-projector.json) 한 행: 원본 항목, TS 묶음, TS 문서, 그리고 같은 원본을 Java 투영기에
 * 넣은 결과. 투영할 원본은 TS 생성기와 같다 — 표가 `whole`이면 전체 목록, 아니면 그 문서의 묶음(묶음에 없는 id면 전체 목록).
 */
record NotionProjectorCases(String label, JsonNode row) {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path DIR =
      Path.of(System.getProperty("scos.fixtures.notion.dir", "../harness/fixtures/notion"));
  static final String SOURCE = "notion-synthetic";
  static final ContentReader INLINE =
      item -> item.content().map(c -> ((RawContent.Inline) c).valueJson());

  /** 문서 비교 값(건너뜀은 id만). */
  record Doc(String id, String uri, String title, String body, String createdAt, String updatedAt) {
    static Doc of(String documentId, Optional<SourceDocument> d) {
      return d.map(
              x -> new Doc(documentId, x.uri(), x.title(), x.body(), x.createdAt(), x.updatedAt()))
          .orElse(new Doc(documentId, null, null, null, null, null));
    }

    static Doc expected(JsonNode e) {
      if (e.path("skipped").asBoolean(false))
        return new Doc(e.get("documentId").asString(), null, null, null, null, null);
      return new Doc(
          e.get("documentId").asString(),
          e.get("uri").asString(),
          e.get("title").asString(),
          e.get("body").asString(),
          e.get("createdAt").asString(),
          e.get("updatedAt").asString());
    }
  }

  static Stream<NotionProjectorCases> all() {
    JsonNode table;
    try {
      table = JSON.readTree(Files.readString(DIR.resolve("expected-projector.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return StreamSupport.stream(table.get("cases").spliterator(), false)
        .map(c -> new NotionProjectorCases(c.get("label").asString(), c));
  }

  List<RawItem> items() {
    List<RawItem> out = new ArrayList<>();
    for (JsonNode n : row.get("items"))
      out.add(
          new RawItem(
              n.get("key").get("externalId").asString(),
              n.get("resourceType").asString(),
              n.get("sourceVersion").asString(),
              Optional.empty(),
              text(n, "sourceCreatedAt"),
              text(n, "sourceUpdatedAt"),
              Instant.parse(n.get("observedAt").asString()),
              n.get("metadata").toString(),
              Optional.of(
                  new RawContent.Inline(
                      "application/json", n.get("content").get("value").toString()))));
    return out;
  }

  /** Java 투영기의 묶음(문서 id → 원본 외부 id). */
  Map<String, List<String>> groups(NotionProjector projector) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    projector
        .groupIntoDocuments(items())
        .forEach((k, v) -> out.put(k, v.stream().map(RawItem::externalId).toList()));
    return out;
  }

  Map<String, List<String>> expectedGroups() {
    Map<String, List<String>> out = new LinkedHashMap<>();
    row.get("groups")
        .properties()
        .forEach(
            e -> {
              List<String> ids = new ArrayList<>();
              e.getValue().forEach(id -> ids.add(id.asString()));
              out.put(e.getKey(), ids);
            });
    return out;
  }

  /** TS 표의 문서 id마다 Java 투영 결과(투영할 원본은 TS 생성기와 같은 규칙). */
  List<Doc> documents(NotionProjector projector) {
    List<RawItem> items = items();
    Map<String, List<RawItem>> groups = projector.groupIntoDocuments(items);
    List<Doc> out = new ArrayList<>();
    for (JsonNode e : row.get("documents")) {
      String id = e.get("documentId").asString();
      List<RawItem> given = row.get("whole").asBoolean() ? items : groups.getOrDefault(id, items);
      out.add(Doc.of(id, projector.project(SOURCE, id, given, INLINE)));
    }
    return out;
  }

  List<Doc> expectedDocuments() {
    List<Doc> out = new ArrayList<>();
    row.get("documents").forEach(e -> out.add(Doc.expected(e)));
    return out;
  }

  private static Optional<String> text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? Optional.empty() : Optional.of(v.asString());
  }

  @Override
  public String toString() {
    return label;
  }
}

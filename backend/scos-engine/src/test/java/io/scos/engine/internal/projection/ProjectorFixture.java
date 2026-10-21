package io.scos.engine.internal.projection;

import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.SourceOrder;
import io.scos.spi.projection.Projector;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.ContextUnit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 투영기 fixture 동등성의 공용 조립(TESTING §3.6): TS가 만든 원본 항목(expected-raw-items.json)을 투영기에 넣어 문서로 묶고 투영한
 * 결과와, 그 문서를 파이프라인에 넣은 유닛을 TS 기대(expected-documents.json·expected-units.json)와 같은 모양의 값으로 돌려준다.
 * 커넥터는 fixture 디렉터리와 투영기만 넘긴다. 단언하지 않는다.
 */
public final class ProjectorFixture {
  private static final ObjectMapper M = new ObjectMapper();
  private static final String JSON_MEDIA = "application/json";

  /** 문서 하나의 비교용 값(TS 기대 JSON과 같은 필드). 건너뛴 문서는 skipped만. */
  public record DocView(
      String documentId,
      boolean skipped,
      String sourceId,
      String uri,
      String title,
      String body,
      String createdAt,
      String updatedAt) {
    static DocView of(String documentId, Optional<SourceDocument> d) {
      return d.map(
              x ->
                  new DocView(
                      documentId,
                      false,
                      x.sourceId(),
                      x.uri(),
                      x.title(),
                      x.body(),
                      x.createdAt(),
                      x.updatedAt()))
          .orElse(new DocView(documentId, true, null, null, null, null, null, null));
    }

    static DocView expected(JsonNode e) {
      String id = e.get("documentId").asString();
      if (e.path("skipped").asBoolean(false))
        return new DocView(id, true, null, null, null, null, null, null);
      return new DocView(
          id,
          false,
          e.get("sourceId").asString(),
          e.get("uri").asString(),
          e.get("title").asString(),
          e.get("body").asString(),
          e.get("createdAt").asString(),
          e.get("updatedAt").asString());
    }
  }

  /** 유닛의 비교용 값(id·본문·토큰·제목·capturedAt). */
  public record UnitView(String id, String content, int tokens, String title, String capturedAt) {
    static UnitView of(ContextUnit u) {
      return new UnitView(u.id(), u.content(), u.tokens(), u.title(), u.provenance().capturedAt());
    }

    static UnitView expected(JsonNode e) {
      return new UnitView(
          e.get("id").asString(),
          e.get("content").asString(),
          e.get("tokens").asInt(),
          e.get("title").asString(),
          e.get("provenance").get("capturedAt").asString());
    }
  }

  private final Path dir;
  private final Projector projector;
  private final String sourceId;

  private ProjectorFixture(Path dir, Projector projector, String sourceId) {
    this.dir = dir;
    this.projector = projector;
    this.sourceId = sourceId;
  }

  /** fixture 디렉터리(TS 생성기 산출), 대상 투영기, 투영할 때 쓰는 소스 id. */
  public static ProjectorFixture of(Path dir, Projector projector, String sourceId) {
    return new ProjectorFixture(dir, projector, sourceId);
  }

  /** 원본을 역순으로 넣어(순서 무관) 묶은 문서 id(정렬 순). */
  public List<String> documentIds() {
    return List.copyOf(groups().keySet());
  }

  /** TS 기대 문서 id(기대 파일 순). */
  public List<String> expectedDocumentIds() {
    List<String> out = new ArrayList<>();
    expectedDocumentNodes().forEach(n -> out.add(n.get("documentId").asString()));
    return out;
  }

  /** 묶은 문서마다 투영한 결과(문서 id 순). */
  public List<DocView> documents() {
    Projector.ContentReader reader =
        item -> item.content().map(c -> ((RawContent.Inline) c).valueJson());
    List<DocView> out = new ArrayList<>();
    for (var e : groups().entrySet())
      out.add(
          DocView.of(e.getKey(), projector.project(sourceId, e.getKey(), e.getValue(), reader)));
    return out;
  }

  /** TS 기대 문서(기대 파일 순). */
  public List<DocView> expectedDocuments() {
    List<DocView> out = new ArrayList<>();
    expectedDocumentNodes().forEach(n -> out.add(DocView.expected(n)));
    return out;
  }

  /** 투영한 문서를 파이프라인에 넣은 유닛(문서 순). */
  public List<UnitView> units() {
    Projector.ContentReader reader =
        item -> item.content().map(c -> ((RawContent.Inline) c).valueJson());
    IngestPipeline pipeline = new IngestPipeline(new JtokkitTokenizer());
    List<UnitView> out = new ArrayList<>();
    for (var e : groups().entrySet())
      projector
          .project(sourceId, e.getKey(), e.getValue(), reader)
          .ifPresent(doc -> pipeline.apply(doc).forEach(u -> out.add(UnitView.of(u))));
    return out;
  }

  public List<UnitView> expectedUnits() {
    List<UnitView> out = new ArrayList<>();
    read("expected-units.json").get("units").forEach(e -> out.add(UnitView.expected(e)));
    return out;
  }

  private Map<String, List<RawItem>> groups() {
    List<RawItem> shuffled = new ArrayList<>(rawItems());
    Collections.reverse(shuffled);
    return projector.groupIntoDocuments(shuffled);
  }

  private JsonNode expectedDocumentNodes() {
    return read("expected-documents.json").get("documents");
  }

  /** 원본 항목. 순서 스킴과 공급자 생성·수정 시각은 있으면 읽는다(Slack은 순서 스킴, Tiro는 시각만 가진다). JSON null은 없음이다. */
  private List<RawItem> rawItems() {
    List<RawItem> items = new ArrayList<>();
    JsonNode raw = read("expected-raw-items.json");
    JsonNode jsonBlobs = raw.path("jsonBlobs");
    for (JsonNode n : raw.get("items")) {
      JsonNode c = n.get("content");
      JsonNode order = n.get("sourceOrder");
      items.add(
          new RawItem(
              n.get("key").get("externalId").asString(),
              n.get("resourceType").asString(),
              n.get("sourceVersion").asString(),
              order == null || order.isNull()
                  ? Optional.empty()
                  : Optional.of(
                      new SourceOrder(
                          order.get("scheme").asString(), order.get("position").asString())),
              optionalString(n, "sourceCreatedAt"),
              optionalString(n, "sourceUpdatedAt"),
              Instant.parse(n.get("observedAt").asString()),
              M.writeValueAsString(n.get("metadata")),
              Optional.of(
                  new RawContent.Inline(
                      c.get("mediaType").asString(), contentJson(c, jsonBlobs)))));
    }
    return items;
  }

  /**
   * 원본 내용의 JSON 텍스트: inline은 값, blob은 TS 생성기가 남긴 JSON blob 내용(jsonBlobs — TS RawContentReader가
   * blob을 읽어 투영하는 것과 같은 내용). JSON 원본 blob인데 jsonBlobs에 없으면 실패한다(생성기 누락). 그 밖의 blob(첨부 바이트)은
   * "null"이다 — 투영 대상이 아니다.
   */
  private static String contentJson(JsonNode content, JsonNode jsonBlobs) {
    if (content.path("storage").asString("").equals("blob")) {
      JsonNode text = jsonBlobs.get(content.path("blobKey").asString(""));
      if (text != null) return text.asString();
      // JSON 원본 blob인데 내용이 없으면 생성기 누락이다 — "null"로 투영하면 원인이 먼 비교 실패로만 드러난다
      if (content.path("mediaType").asString("").equals(JSON_MEDIA))
        throw new IllegalStateException(
            "jsonBlobs에 JSON 원본 blob이 없다: " + content.path("blobKey").asString(""));
      return "null";
    }
    return M.writeValueAsString(content.get("value"));
  }

  private static Optional<String> optionalString(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? Optional.empty() : Optional.of(value.asString());
  }

  private JsonNode read(String name) {
    try {
      return M.readTree(Files.readString(dir.resolve(name)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

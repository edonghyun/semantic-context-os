package io.scos.collection.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 커넥터 테스트가 TS fixture와 비교하는 값(TESTING §3.5 (1) — 커넥터 사이에 중복되던 연결): TS 원본 항목·본문·거부·체크포인트·run 보고의 모양은
 * 커넥터와 무관하다. 각 레코드는 Java 값에서 만드는 {@code of}와 TS fixture JSON에서 만드는 {@code expected}를 갖고, 같은 필드로
 * 비교한다.
 *
 * <p>비교는 바이트 단위다: JSON 값(inline 본문·metadata·거부 위치·체크포인트)은 JS 순회 순서로 다시 쓴 문자열({@link JsJson})로 비교하고,
 * 시각은 TS `Date#toISOString` 모양으로 비교한다. 커넥터 고유의 페이지·요청 모양은 각 커넥터의 TS run 지원(`NotionTsRuns`·
 * `JiraTsRuns`)에 둔다.
 */
public final class TsRawViews {
  private static final DateTimeFormatter ISO_MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private TsRawViews() {}

  /** TS Date#toISOString 모양(밀리초, Z). */
  public static String iso(Instant at) {
    return ISO_MILLIS.format(at);
  }

  /**
   * 이 예외로 끝난 run에 남을 errorKind — 제품 매핑({@code CollectionRuntime.classify}, CONTRACTS "run errorKind
   * 매핑") 그대로.
   */
  public static String runErrorKind(RuntimeException failure) {
    return CollectionRuntime.classify(failure);
  }

  /** 페이지들을 차례로 적용한 뒤 외부 id별 마지막 원본(upsert면 그 원본, delete면 빠진다) — 원본 저장소가 남길 active 원본. */
  public static List<ItemView> lastItems(List<CollectionPage> pages) {
    Map<String, RawItem> last = new LinkedHashMap<>();
    for (CollectionPage p : pages)
      for (RawMutation m : p.mutations())
        if (m instanceof RawMutation.Upsert u) last.put(m.externalId(), u.item());
        else last.remove(m.externalId());
    return last.values().stream().map(ItemView::of).toList();
  }

  /** 순서 비교 값: "scheme position". */
  public static String order(SourceOrder o) {
    return o.scheme() + " " + o.position();
  }

  static String order(JsonNode o) {
    return o.get("scheme").asString() + " " + o.get("position").asString();
  }

  /** 체크포인트: 스키마와 값 JSON 원문(키 순서까지). 기대값은 TS 값을 JS 순서로 다시 쓴 것이다. */
  public record CheckpointView(int schemaVersion, String valueJson) {
    public static CheckpointView of(Checkpoint c) {
      return new CheckpointView(c.schemaVersion(), c.valueJson());
    }

    /** TS 체크포인트 JSON({schemaVersion, value}). */
    public static CheckpointView expected(JsonNode c) {
      return new CheckpointView(c.get("schemaVersion").asInt(), JsJson.stringify(c.get("value")));
    }

    public Checkpoint checkpoint() {
      return new Checkpoint(schemaVersion, valueJson);
    }
  }

  /** 거부: 위치는 JSON 원문(키 순서까지), 관찰 시각은 TS 모양. */
  public record RejectionView(
      String failureKind, String message, String providerLocator, String observedAt) {
    public static RejectionView of(RejectedItem r) {
      return new RejectionView(
          r.failureKind().wire(),
          r.message(),
          r.providerLocatorJson().orElseThrow(),
          iso(r.observedAt()));
    }

    public static RejectionView expected(JsonNode r) {
      return new RejectionView(
          r.get("failureKind").asString(),
          r.get("message").asString(),
          JsJson.stringify(r.get("providerLocator")),
          r.get("observedAt").asString());
    }

    /** 관찰 시각을 남기지 않는 TS 거부(Jira 생성기)를 그 run의 고정 시각 at으로 읽는다. */
    public static RejectionView expectedAt(JsonNode r, Instant at) {
      return new RejectionView(
          r.get("failureKind").asString(),
          r.get("message").asString(),
          JsJson.stringify(r.get("providerLocator")),
          iso(at));
    }
  }

  /** 본문: inline이면 값 JSON 원문(TS JSON.stringify 바이트), blob이면 미디어 유형·sha256·크기. */
  public record ContentView(
      String storage, String mediaType, String valueJson, String sha256, long size) {
    public static ContentView of(RawContent c) {
      return switch (c) {
        case RawContent.Inline i ->
            new ContentView("inline", i.mediaType(), i.valueJson(), null, 0);
        case RawContent.Blob b ->
            new ContentView("blob", b.mediaType(), null, b.sha256(), b.size());
      };
    }

    public static ContentView expected(JsonNode c) {
      return c.get("storage").asString().equals("inline")
          ? new ContentView(
              "inline", c.get("mediaType").asString(), JsJson.stringify(c.get("value")), null, 0)
          : new ContentView(
              "blob",
              c.get("mediaType").asString(),
              null,
              c.get("sha256").asString(),
              c.get("size").asLong());
    }
  }

  /** 원본 항목: 버전·순서·공급자 시각·metadata(원문)·본문·관찰 시각. */
  public record ItemView(
      String externalId,
      String resourceType,
      String sourceVersion,
      String order,
      String sourceCreatedAt,
      String sourceUpdatedAt,
      String metadataJson,
      ContentView content,
      String observedAt) {
    public static ItemView of(RawItem a) {
      return new ItemView(
          a.externalId(),
          a.resourceType(),
          a.sourceVersion(),
          a.sourceOrder().map(TsRawViews::order).orElse(null),
          a.sourceCreatedAt().orElse(null),
          a.sourceUpdatedAt().orElse(null),
          a.metadataJson(),
          ContentView.of(a.content().orElseThrow()),
          iso(a.observedAt()));
    }

    public static ItemView expected(JsonNode e) {
      JsonNode order = e.get("sourceOrder");
      return new ItemView(
          e.get("key").get("externalId").asString(),
          e.get("resourceType").asString(),
          e.get("sourceVersion").asString(),
          order == null || order.isNull() ? null : TsRawViews.order(order),
          text(e, "sourceCreatedAt"),
          text(e, "sourceUpdatedAt"),
          JsJson.stringify(e.get("metadata")),
          ContentView.expected(e.get("content")),
          e.get("observedAt").asString());
    }

    private static String text(JsonNode e, String field) {
      JsonNode v = e.get(field);
      return v == null || v.isNull() ? null : v.asString();
    }
  }

  /** run 보고(TS 보고 JSON과 같은 필드, run id 제외). */
  public record ReportView(
      String status,
      int pagesApplied,
      int upserted,
      int deleted,
      int unchanged,
      int rejected,
      int errorCount) {
    public static ReportView of(CollectionRuntime.Report r) {
      return new ReportView(
          r.status().wire(),
          r.pagesApplied(),
          r.upserted(),
          r.deleted(),
          r.unchanged(),
          r.rejected(),
          r.errorCount());
    }

    public static ReportView expected(JsonNode r) {
      return new ReportView(
          r.get("status").asString(),
          r.get("pagesApplied").asInt(),
          r.get("upserted").asInt(),
          r.get("deleted").asInt(),
          r.get("unchanged").asInt(),
          r.get("rejected").asInt(),
          r.get("errorCount").asInt());
    }
  }
}

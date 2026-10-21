package io.scos.connector.notion.internal;

import io.scos.collection.internal.TsRawViews;
import io.scos.collection.internal.TsRawViews.CheckpointView;
import io.scos.collection.internal.TsRawViews.ItemView;
import io.scos.collection.internal.TsRawViews.RejectionView;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawMutation;
import io.scos.support.FakeHttpServer.Recorded;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Notion 테스트가 TS 결과와 비교하는 Notion 고유 값과 TS fixture 읽기(TESTING §3.5 (2) — 시나리오 상태와 무관한 독립 값): 페이지(변경·
 * 거부·체크포인트), 요청(메서드·경로·쿼리·POST 본문), TS run 묶음. 커넥터 공통 값(원본·본문·거부·체크포인트·보고)은 공용 {@link TsRawViews}다.
 */
final class NotionTsRuns {
  private NotionTsRuns() {}

  /** roots 모드 첫 수집(expected-raw-items.json 최상위 — secondRun·thirdRun 포함). */
  static JsonNode rootsFile() {
    return NotionFixtures.read("expected-raw-items.json");
  }

  /** 행 둘째 페이지 중단 → 재개(expected-resume.json). */
  static JsonNode resumeFile() {
    return NotionFixtures.read("expected-resume.json");
  }

  /** 변경 하나: upsert는 외부 id, delete는 버전·순서·일어난 시각까지. */
  record MutationView(
      String type, String externalId, String sourceVersion, String order, String occurredAt) {
    static MutationView of(RawMutation m) {
      return switch (m) {
        case RawMutation.Upsert u -> new MutationView("upsert", u.externalId(), null, null, null);
        case RawMutation.Delete d ->
            new MutationView(
                "delete",
                d.externalId(),
                d.sourceVersion().orElseThrow(),
                TsRawViews.order(d.sourceOrder().orElseThrow()),
                TsRawViews.iso(d.occurredAt().orElseThrow()));
      };
    }

    static MutationView expected(JsonNode m) {
      if (m.get("type").asString().equals("upsert"))
        return new MutationView("upsert", m.get("externalId").asString(), null, null, null);
      return new MutationView(
          "delete",
          m.get("externalId").asString(),
          m.get("sourceVersion").asString(),
          m.get("sourceOrder").get("scheme").asString()
              + " "
              + m.get("sourceOrder").get("position").asString(),
          m.get("occurredAt").asString());
    }
  }

  record PageView(
      List<MutationView> mutations, List<RejectionView> rejections, CheckpointView nextCheckpoint) {
    static PageView of(CollectionPage page) {
      return new PageView(
          page.mutations().stream().map(MutationView::of).toList(),
          page.rejections().stream().map(RejectionView::of).toList(),
          CheckpointView.of(page.nextCheckpoint()));
    }

    static PageView expected(JsonNode p) {
      List<MutationView> mutations = new ArrayList<>();
      p.get("mutations").forEach(m -> mutations.add(MutationView.expected(m)));
      List<RejectionView> rejections = new ArrayList<>();
      p.get("rejections").forEach(r -> rejections.add(RejectionView.expected(r)));
      return new PageView(mutations, rejections, CheckpointView.expected(p.get("nextCheckpoint")));
    }
  }

  /** 요청: 메서드·경로·디코드한 쿼리·POST 본문 원문(GET은 null). */
  record RequestView(String method, String path, Map<String, String> params, String body) {
    static RequestView of(Recorded r) {
      return new RequestView(
          r.method(), r.path(), r.params(), r.body().isEmpty() ? null : r.body());
    }

    /**
     * TS 클라이언트 호출 하나({operation, pageId|blockId|…, cursor?, inTrash?})가 보내는 HTTP 요청 — 경로·쿼리·본문 모양은
     * NotionApiClient와 TS가 같다는 것을 {@code NotionApiClientTest}가 따로 고정한다.
     */
    static RequestView ofTsCall(JsonNode call) {
      Optional<String> cursor =
          call.has("cursor") ? Optional.of(call.get("cursor").asString()) : Optional.empty();
      return switch (call.get("operation").asString()) {
        case "retrievePage" -> get("/v1/pages/" + call.get("pageId").asString(), Map.of());
        case "retrieveDatabase" ->
            get("/v1/databases/" + call.get("databaseId").asString(), Map.of());
        case "retrieveDataSource" ->
            get("/v1/data_sources/" + call.get("dataSourceId").asString(), Map.of());
        case "listBlockChildren" -> {
          Map<String, String> params = new LinkedHashMap<>();
          params.put("page_size", "100");
          cursor.ifPresent(c -> params.put("start_cursor", c));
          yield get("/v1/blocks/" + call.get("blockId").asString() + "/children", params);
        }
        case "queryDataSource" ->
            new RequestView(
                "POST",
                "/v1/data_sources/" + call.get("dataSourceId").asString() + "/query",
                Map.of(),
                "{\"page_size\":100"
                    + cursor.map(c -> ",\"start_cursor\":\"" + c + "\"").orElse("")
                    + "}");
        case "searchPages" ->
            new RequestView(
                "POST",
                "/v1/search",
                Map.of(),
                "{\"filter\":{\"property\":\"object\",\"value\":\"page\""
                    + (call.has("inTrash") ? ",\"in_trash\":true" : "")
                    + "},\"sort\":{\"direction\":\"descending\",\"timestamp\":\"last_edited_time\"},\"page_size\":100"
                    + cursor.map(c -> ",\"start_cursor\":\"" + c + "\"").orElse("")
                    + "}");
        default -> throw new IllegalArgumentException(call.toString());
      };
    }

    private static RequestView get(String path, Map<String, String> params) {
      return new RequestView("GET", path, params, null);
    }

    static RequestView expected(JsonNode r) {
      Map<String, String> params = new LinkedHashMap<>();
      r.get("params").properties().forEach(e -> params.put(e.getKey(), e.getValue().asString()));
      return new RequestView(
          r.get("method").asString(),
          r.get("path").asString(),
          params,
          r.get("body").isNull() ? null : r.get("body").asString());
    }
  }

  /** TS가 같은 데이터셋에서 낸 run 하나(페이지·원본·요청·저장된 체크포인트(페이지를 하나도 커밋하지 않았으면 없음)·거부). */
  record TsRun(
      List<PageView> pages,
      List<ItemView> items,
      List<RequestView> requests,
      Optional<CheckpointView> checkpoint,
      List<RejectionView> rejections) {
    static TsRun of(JsonNode run) {
      List<PageView> pages = new ArrayList<>();
      run.get("pages").forEach(p -> pages.add(PageView.expected(p)));
      List<ItemView> items = new ArrayList<>();
      if (run.has("items")) run.get("items").forEach(i -> items.add(ItemView.expected(i)));
      List<RequestView> requests = new ArrayList<>();
      run.get("requests").forEach(r -> requests.add(RequestView.expected(r)));
      List<RejectionView> rejections = new ArrayList<>();
      if (run.has("rejections"))
        run.get("rejections").forEach(r -> rejections.add(RejectionView.expected(r)));
      JsonNode stored = run.get("checkpoint").get("checkpoint");
      return new TsRun(
          pages,
          items,
          requests,
          stored.isNull() ? Optional.empty() : Optional.of(CheckpointView.expected(stored)),
          rejections);
    }

    /** roots 모드 첫 수집(expected-raw-items.json 최상위). */
    static TsRun firstRoots() {
      return of(rootsFile());
    }
  }
}

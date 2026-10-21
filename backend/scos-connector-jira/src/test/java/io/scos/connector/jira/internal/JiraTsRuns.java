package io.scos.connector.jira.internal;

import io.scos.collection.internal.TsRawViews.ItemView;
import io.scos.collection.internal.TsRawViews.RejectionView;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawMutation;
import io.scos.support.FakeHttpServer.Recorded;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Jira 테스트가 TS 결과와 비교하는 값과 TS fixture 읽기(TESTING §3.5 (2) — 시나리오 상태와 무관한 독립 값): 페이지·거부·본문·원본·요청의 비교
 * 레코드, TS run 묶음, 이름 있는 TS 기대 읽기. 3·5·6층이 같은 읽기를 쓴다.
 */
final class JiraTsRuns {
  private JiraTsRuns() {}

  /** TS 중단 run(40005)이 남긴 저장 체크포인트 JSON(expected-resume.json firstRun). */
  static JsonNode crashedRunCheckpoint() {
    return JiraFixtures.read("expected-resume.json")
        .get("firstRun")
        .get("checkpoint")
        .get("checkpoint");
  }

  /** TS fixture의 문자열 배열. */
  static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return out;
  }

  /** TS 생성기의 중단 지점 경로(expected-resume.json crashAt) — 40004·40005 배치 안의 40005. */
  static String crashPath() {
    return JiraFixtures.read("expected-resume.json").get("crashAt").get("path").asString();
  }

  /** TS 첫 수집(한 번에 끝까지)의 저장 체크포인트 JSON(expected-raw-items.json). */
  static JsonNode firstCollectionCheckpoint() {
    return JiraFixtures.read("expected-raw-items.json").get("checkpoint").get("checkpoint");
  }

  /** 페이지 하나: upsert한 외부 id(순서대로), 거부, 다음 체크포인트. */
  record PageView(List<String> upserted, List<RejectionView> rejections, JsonNode nextCheckpoint) {
    static PageView of(CollectionPage page) {
      return new PageView(
          page.mutations().stream().map(RawMutation::externalId).toList(),
          page.rejections().stream().map(RejectionView::of).toList(),
          checkpointJson(page.nextCheckpoint()));
    }

    static PageView expected(JsonNode p) {
      List<String> ids = new ArrayList<>();
      p.get("upserted").forEach(n -> ids.add(n.asString()));
      List<RejectionView> rejections = new ArrayList<>();
      p.get("rejections")
          .forEach(r -> rejections.add(RejectionView.expectedAt(r, JiraScenario.NOW)));
      return new PageView(ids, rejections, p.get("nextCheckpoint"));
    }
  }

  /** 체크포인트 비교 값(JSON 값 비교 — 키 순서는 보지 않는다. 저장이 jsonb라 순서는 남지 않는다). */
  static JsonNode checkpointJson(Checkpoint c) {
    return JiraFixtures.JSON
        .createObjectNode()
        .put("schemaVersion", c.schemaVersion())
        .set("value", JiraFixtures.JSON.readTree(c.valueJson()));
  }

  record RequestView(String path, Map<String, String> params) {
    static RequestView of(Recorded r) {
      return new RequestView(r.path(), r.params());
    }

    static RequestView expected(JsonNode r) {
      Map<String, String> params = new LinkedHashMap<>();
      r.get("params").properties().forEach(e -> params.put(e.getKey(), e.getValue().asString()));
      return new RequestView(r.get("path").asString(), params);
    }
  }

  /** TS가 같은 데이터셋에서 낸 결과 한 묶음(expected-raw-items.json의 첫 수집, expected-resume.json의 둘째 run). */
  record TsRun(
      List<PageView> pages, List<ItemView> items, List<RequestView> requests, JsonNode checkpoint) {
    static TsRun firstCollection() {
      JsonNode root = JiraFixtures.read("expected-raw-items.json");
      return new TsRun(
          pages(root.get("pages")),
          items(root.get("items")),
          requests(root.get("requests")),
          root.get("checkpoint").get("checkpoint"));
    }

    /** 이 run의 페이지가 upsert한 원본(TS 최종 원본 가운데) — 재개 run만의 원본을 정확히 비교하려고. */
    List<ItemView> upsertedItems() {
      Set<String> ids = new HashSet<>();
      pages.forEach(p -> ids.addAll(p.upserted()));
      return items.stream().filter(i -> ids.contains(i.externalId())).toList();
    }

    /** 40005에서 중단된 첫 run이 남긴 체크포인트에서 재개한 둘째 run. 원본은 두 run을 합친 최종 active 원본이다. */
    static TsRun resumedAfterCrash() {
      JsonNode root = JiraFixtures.read("expected-resume.json");
      JsonNode second = root.get("secondRun");
      return new TsRun(
          pages(second.get("pages")),
          items(root.get("items")),
          requests(second.get("requests")),
          second.get("checkpoint").get("checkpoint"));
    }

    /** 중단된 첫 run이 남긴 체크포인트. */
    static Checkpoint crashCheckpoint() {
      JsonNode c = crashedRunCheckpoint();
      return new Checkpoint(c.get("schemaVersion").asInt(), c.get("value").toString());
    }

    private static List<PageView> pages(JsonNode array) {
      List<PageView> out = new ArrayList<>();
      array.forEach(p -> out.add(PageView.expected(p)));
      return out;
    }

    private static List<ItemView> items(JsonNode array) {
      List<ItemView> out = new ArrayList<>();
      array.forEach(i -> out.add(ItemView.expected(i)));
      return out;
    }

    private static List<RequestView> requests(JsonNode array) {
      List<RequestView> out = new ArrayList<>();
      array.forEach(r -> out.add(RequestView.expected(r)));
      return out;
    }
  }
}

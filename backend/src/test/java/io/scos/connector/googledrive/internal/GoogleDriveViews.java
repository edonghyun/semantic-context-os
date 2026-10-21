package io.scos.connector.googledrive.internal;

import io.scos.collection.internal.TsRawViews.ItemView;
import io.scos.collection.internal.TsRawViews.RejectionView;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RunErrorKind;
import io.scos.support.FakeHttpServer.Recorded;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Drive fixture 비교 값(TESTING §3.6 3층): Java 수집 결과와 TS 기대 JSON(expected-*.json)을 같은 모양의 값으로 읽는다.
 * 커넥터와 무관한 원본·본문·거부 값은 공용 {@link io.scos.collection.internal.TsRawViews}를 쓰고, 여기에는 Drive 고유의 페이지
 * 변경·체크포인트, 요청(origin 다섯), 실패, TS run 하나를 둔다. 단언하지 않는다.
 */
final class GoogleDriveViews {
  static final Instant NOW = GoogleDriveScenario.NOW;

  private GoogleDriveViews() {}

  /** 페이지 하나: 변경(`upsert:<id>`·`delete:<id>`, 순서대로), 거부, 다음 체크포인트. */
  record PageView(List<String> mutations, List<RejectionView> rejections, JsonNode nextCheckpoint) {
    static PageView of(CollectionPage page) {
      return new PageView(
          page.mutations().stream()
              .map(m -> (m instanceof RawMutation.Upsert ? "upsert:" : "delete:") + m.externalId())
              .toList(),
          page.rejections().stream().map(RejectionView::of).toList(),
          checkpointJson(page.nextCheckpoint()));
    }

    static PageView expected(JsonNode p) {
      List<String> mutations = new ArrayList<>();
      p.get("mutations").forEach(n -> mutations.add(n.asString()));
      List<RejectionView> rejections = new ArrayList<>();
      p.get("rejections").forEach(r -> rejections.add(rejection(r)));
      return new PageView(mutations, rejections, p.get("nextCheckpoint"));
    }
  }

  /** TS 거부: Drive 생성기는 관찰 시각을 남기지 않는 거부도 있다 — 그 run의 고정 시각(데이터셋 now)으로 읽는다. */
  static RejectionView rejection(JsonNode r) {
    return r.has("observedAt") ? RejectionView.expected(r) : RejectionView.expectedAt(r, NOW);
  }

  static JsonNode checkpointJson(Checkpoint c) {
    return GoogleDriveFixtures.JSON
        .createObjectNode()
        .put("schemaVersion", c.schemaVersion())
        .set("value", GoogleDriveFixtures.JSON.readTree(c.valueJson()));
  }

  /** 실패: TS 오류 이름(일반 Error는 "Error", 공급자 호출은 "ProviderCallError"), 메시지, 공급자 실패 분류. */
  record ErrorView(String name, String message, JsonNode failure) {
    static ErrorView of(RuntimeException e) {
      if (e instanceof CallFailureException p) {
        CallFailure f = p.failure();
        return new ErrorView(
            "ProviderCallError",
            f.message(),
            GoogleDriveFixtures.JSON
                .createObjectNode()
                .put("kind", f.kind().wire())
                .put("retryable", f.retryable())
                .put("message", f.message()));
      }
      String name = e instanceof RunErrorKind k ? k.runErrorKind() : e.getClass().getSimpleName();
      return new ErrorView(name, e.getMessage(), GoogleDriveFixtures.JSON.nullNode());
    }

    static ErrorView expected(JsonNode e) {
      return new ErrorView(e.get("name").asString(), e.get("message").asString(), e.get("failure"));
    }
  }

  /** 요청 하나: 메서드, origin(경로가 정한다 — 가짜는 한 주소다), 경로, 디코드한 쿼리. */
  record RequestView(String method, String origin, String path, Map<String, String> params) {
    /** 가짜가 받은 경로의 접두가 제품이 고른 origin이다 — 접두를 데이터셋 origin으로 되돌리고 뗀다. */
    static RequestView of(Recorded r) {
      for (var e : GoogleDriveAssembly.ORIGIN_PREFIXES.entrySet())
        if (r.path().startsWith(e.getValue() + "/"))
          return new RequestView(
              r.method(),
              GoogleDriveFixtures.dataset().get("origins").get(e.getKey()).asString(),
              r.path().substring(e.getValue().length()),
              r.params());
      throw new IllegalArgumentException("origin 접두가 없는 요청: " + r.path());
    }

    static RequestView expected(JsonNode r) {
      Map<String, String> params = new LinkedHashMap<>();
      r.get("params").properties().forEach(e -> params.put(e.getKey(), e.getValue().asString()));
      return new RequestView(
          r.get("method").asString(), r.get("origin").asString(), r.get("path").asString(), params);
    }
  }

  /** TS가 같은 데이터셋에서 낸 run 하나(expected-*.json). */
  record TsRun(
      List<PageView> pages,
      List<ItemView> items,
      List<RequestView> requests,
      List<Duration> waits,
      JsonNode checkpoint) {
    static TsRun of(String file) {
      return of(GoogleDriveFixtures.read(file));
    }

    static TsRun of(JsonNode root) {
      List<PageView> pages = new ArrayList<>();
      root.get("pages").forEach(p -> pages.add(PageView.expected(p)));
      List<ItemView> items = new ArrayList<>();
      if (root.has("items")) root.get("items").forEach(i -> items.add(ItemView.expected(i)));
      List<RequestView> requests = new ArrayList<>();
      root.get("requests").forEach(r -> requests.add(RequestView.expected(r)));
      List<Duration> waits = new ArrayList<>();
      root.get("waits").forEach(w -> waits.add(Duration.ofMillis(w.asLong())));
      JsonNode checkpoint = root.get("checkpoint");
      return new TsRun(
          pages, items, requests, waits, checkpoint.isNull() ? null : checkpoint.get("checkpoint"));
    }

    /** TS 최종 원본 가운데 손으로 적은 id의 원본(적은 순서). TS 원본에 없는 id면 준비 실패로 멈춘다. */
    List<ItemView> itemsWith(String... externalIds) {
      List<ItemView> out = new ArrayList<>();
      for (String id : externalIds)
        out.add(
            items.stream()
                .filter(i -> i.externalId().equals(id))
                .findFirst()
                .orElseThrow(
                    () -> new IllegalStateException("given 준비 실패: TS 최종 원본에 " + id + "가 없다")));
      return out;
    }

    /** 이 run을 시작한 체크포인트로 쓸 수 있는 최종 체크포인트. */
    Checkpoint finalCheckpoint() {
      return new Checkpoint(
          checkpoint.get("schemaVersion").asInt(), checkpoint.get("value").toString());
    }
  }
}

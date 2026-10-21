package io.scos.demo;

import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.support.FakeHttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 손으로 띄우는 가짜 공급자 프로세스(공개 데모 demo/는 이것 대신 demo/embedder를 쓴다): 합성 Slack 데이터셋(harness/fixtures/p4)을
 * Slack Web API로, 해싱 임베딩을 Gemini batchEmbedContents로 로컬 루프백에서만 응답한다. 실제 외부 호출은 없다. 실행 인자: {@code
 * <dataset.json> <endpoints.out>} — 두 기본 URL과 Slack 토큰을 endpoints 파일(셸 export 형식)에 쓰고 종료 신호까지 산다.
 */
public final class DemoProviders {
  private static final Logger log = LoggerFactory.getLogger(DemoProviders.class);
  static final String EMBED = "/v1beta/models/gemini-embedding-001:batchEmbedContents";
  static final int DIM = 768;
  static final ObjectMapper JSON = new ObjectMapper();

  private DemoProviders() {}

  public static void main(String[] args) throws Exception {
    Path dataset = Path.of(args[0]);
    Path endpoints = Path.of(args[1]);
    FakeSlackServer slack = new FakeSlackServer(dataset);
    FakeHttpServer gemini = new FakeHttpServer();
    gemini.on("POST", EMBED, r -> FakeHttpServer.ok(embeddings(JSON.readTree(r.body()))));
    Files.writeString(
        endpoints,
        "export SCOS_SLACK_BASE_URL="
            + slack.baseUrl()
            + "\nexport SCOS_GEMINI_BASE_URL="
            + gemini.baseUrl()
            + "\nexport SLACK_DEMO_TOKEN="
            + slack.token()
            + "\n",
        StandardCharsets.UTF_8);
    log.info("demo providers: slack={} gemini={}", slack.baseUrl(), gemini.baseUrl());
    new CountDownLatch(1).await(); // 종료 신호(SIGTERM)까지
  }

  /** 요청마다 텍스트의 글자 bigram 해싱 벡터(768차원, L2 정규화) — 겹치는 낱말이 많을수록 가깝다. 결정론. */
  static String embeddings(JsonNode body) {
    StringBuilder sb = new StringBuilder("{\"embeddings\":[");
    JsonNode requests = body.get("requests");
    for (int i = 0; i < requests.size(); i++) {
      String text = requests.get(i).path("content").path("parts").get(0).path("text").asString();
      double[] v = hashingVector(text);
      sb.append(i > 0 ? "," : "").append("{\"values\":[");
      for (int d = 0; d < DIM; d++) sb.append(d > 0 ? "," : "").append(v[d]);
      sb.append("]}");
    }
    return sb.append("]}").toString();
  }

  static double[] hashingVector(String text) {
    double[] v = new double[DIM];
    String compact = text.replaceAll("\\s+", "");
    for (int i = 0; i + 1 < compact.length(); i++)
      v[Math.floorMod(compact.substring(i, i + 2).hashCode(), DIM)] += 1;
    double norm = 0;
    for (double x : v) norm += x * x;
    norm = Math.sqrt(norm);
    if (norm == 0) v[0] = 1;
    else for (int d = 0; d < DIM; d++) v[d] /= norm;
    return v;
  }
}

package io.scos.harness.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 언어 중립 fixture 묶음(harness/fixtures/p1): documents.json(유닛), calls.json(도구 호출), cassette.json(모델
 * 응답), determinism.json(F-1~F-7).
 */
public final class FixtureSpec {
  /** intentionalDifference가 있으면 구현 간 차이를 "의도적 차이"로 보고한다(조용히 제외하지 않는다). */
  public record Call(
      String name,
      String tool,
      JsonNode args,
      java.util.Optional<String> intentionalDifference,
      java.util.Optional<KnownFailure> knownFailure,
      java.util.Optional<ComparePolicy> compare) {}

  /** 기한부 알려진 실패(리뷰 I-3): 사유와 만료 단계. 목록 밖 실패·목록 안 통과 둘 다 실패다. */
  public record KnownFailure(String reason, String until) {}

  /**
   * 호출별 비교 정책(리뷰 I-2): 사유를 적은 호출만 완화하되 무엇을 잠그는지 선언한다. "ranked-list" = 점수 목록을 식별자로 짝지어 순위 차 ≤
   * maxRankShift·점수 절대 오차 ≤ scoreAbsTolerance, 나머지 필드 정확. "unit-multiset" = units를 다중집합으로, exact
   * 필드는 정확 일치. 정책 위반은 실패다.
   */
  public record ComparePolicy(
      String policy,
      int maxRankShift,
      double scoreAbsTolerance,
      List<String> exact,
      String reason) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Path dir;
  private final JsonNode calls;
  private final JsonNode documents;

  private FixtureSpec(Path dir, JsonNode calls, JsonNode documents) {
    this.dir = dir;
    this.calls = calls;
    this.documents = documents;
  }

  public static FixtureSpec load(Path dir) throws IOException {
    JsonNode calls = MAPPER.readTree(Files.readString(dir.resolve("calls.json")));
    JsonNode docs = MAPPER.readTree(Files.readString(dir.resolve("documents.json")));
    for (String key : List.of("version", "now", "spaceId", "calls"))
      if (!calls.has(key)) throw new IOException("calls.json missing " + key);
    for (String key : List.of("version", "now", "units", "embedding"))
      if (!docs.has(key)) throw new IOException("documents.json missing " + key);
    if (!calls.get("now").asText().equals(docs.get("now").asText()))
      throw new IOException("calls.now != documents.now");
    return new FixtureSpec(dir, calls, docs);
  }

  public Path dir() {
    return dir;
  }

  public Path documentsPath() {
    return dir.resolve("documents.json");
  }

  public Path cassettePath() {
    return dir.resolve("cassette.json");
  }

  public Path determinismPath() {
    return dir.resolve("determinism.json");
  }

  public Path expectedDir(String implementationId) {
    return dir.resolve("expected").resolve(implementationId);
  }

  public String nowIso() {
    return calls.get("now").asText();
  }

  public String spaceId() {
    return calls.get("spaceId").asText();
  }

  public int unitCount() {
    return documents.get("units").size();
  }

  public int embeddingDim() {
    return documents.get("embedding").get("dim").asInt();
  }

  public List<Call> calls() {
    List<Call> out = new ArrayList<>();
    for (JsonNode c : calls.get("calls")) {
      JsonNode kf = c.get("knownFailure");
      JsonNode cp = c.get("compare");
      java.util.Optional<ComparePolicy> policy = java.util.Optional.empty();
      if (cp != null) {
        List<String> exact = new ArrayList<>();
        cp.path("exact").forEach(x -> exact.add(x.asText()));
        policy =
            java.util.Optional.of(
                new ComparePolicy(
                    cp.path("policy").asText(),
                    cp.path("maxRankShift").asInt(0),
                    cp.path("scoreAbsTolerance").asDouble(0),
                    exact,
                    cp.path("reason").asText("")));
      }
      out.add(
          new Call(
              c.get("name").asText(),
              c.get("tool").asText(),
              c.get("args"),
              java.util.Optional.ofNullable(c.get("intentionalDifference")).map(JsonNode::asText),
              kf == null
                  ? java.util.Optional.empty()
                  : java.util.Optional.of(
                      new KnownFailure(kf.path("reason").asText(), kf.path("until").asText())),
              policy));
    }
    return out;
  }

  public JsonNode units() {
    return documents.get("units");
  }
}

package io.scos.harness.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MCP 응답 비교기(CONTRACTS F-7·F-8, 사용자 결정 2026-10-13). 파싱 후 구조 비교(키 순서 무시, 생략 필드 ≠ null).
 *
 * <ul>
 *   <li>도구 응답의 content[0].text가 JSON이면 파싱해 비교한다(isError 응답은 문자열 그대로).
 *   <li>점수 배열({@code hits}·{@code related}·{@code units}처럼 {@code score}/{@code rankScore}를 가진 객체
 *       목록)은 **동점군** 단위로 비교한다: 기대 목록을 점수 차 ≤ tieEpsilon(1e-12)인 연속 구간으로 묶고, 같은 구간 안에서는 순서 없는 집합(식별자
 *       기준)으로, 구간 사이 순서는 그대로. 하네스가 순위를 임의로 정리하지 않는다 — 집합 비교는 기대 점수가 같을 때만.
 *   <li>숫자: 점수 필드는 상대 오차 scoreRelTolerance(1e-6), 그 외 숫자는 값 동일(정수·실수 표현 차이는 허용: 2 == 2.0).
 * </ul>
 */
public final class ResponseComparator {
  public record Policy(
      double scoreRelTolerance,
      double tieEpsilon,
      Set<String> scoreFields,
      Set<String> identityFields) {
    public static Policy standard() {
      return new Policy(
          1e-6, 1e-12, Set.of("score", "rankScore"), Set.of("documentId", "unitId", "id"));
    }
  }

  public record Difference(String path, String message) {
    @Override
    public String toString() {
      return path + ": " + message;
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Policy policy;

  public ResponseComparator(Policy policy) {
    this.policy = policy;
  }

  /** 도구 응답(result 객체) 두 개를 비교해 차이 목록을 돌려준다(빈 목록 = 일치). */
  public List<Difference> compareToolResult(JsonNode expected, JsonNode actual) {
    List<Difference> diffs = new ArrayList<>();
    if (isError(expected) || isError(actual)) {
      // ADR-0010 규칙 3: 오류 응답은 isError와 오류 종류만 비교한다(검증기 문구는 구현·로케일마다 다르다)
      String e = errorKind(expected), a = errorKind(actual);
      if (!e.equals(a)) diffs.add(new Difference("$.error", "kind " + e + " vs " + a));
      return diffs;
    }
    compare("$", unwrap(expected), unwrap(actual), diffs);
    return diffs;
  }

  /**
   * 호출별 정책 비교(리뷰 I-2). ranked-list: 점수 목록 원소를 식별자(점수 뺀 객체)로 짝지어 집합 동일·순위 차 ≤ maxRankShift·점수 절대 오차
   * ≤ scoreAbsTolerance, 나머지 필드는 표준 비교. unit-multiset: 목록(units)을 다중집합으로 비교, exact 필드는 표준 비교(선언 안 된
   * 필드는 실패). 오류 응답이면 종류 비교.
   */
  public List<Difference> compareWithPolicy(
      JsonNode expected, JsonNode actual, io.scos.harness.fixture.FixtureSpec.ComparePolicy p) {
    if (isError(expected) || isError(actual)) return compareToolResult(expected, actual);
    JsonNode e = unwrap(expected).path("content").path(0).path("json");
    JsonNode a = unwrap(actual).path("content").path(0).path("json");
    List<Difference> diffs = new ArrayList<>();
    if (e.isMissingNode() || a.isMissingNode()) {
      diffs.add(new Difference("$", "policy requires JSON tool result"));
      return diffs;
    }
    String listField =
        switch (p.policy()) {
          case "ranked-list" -> firstScoredListField(e);
          case "unit-multiset" -> "units";
          default -> throw new IllegalArgumentException("unknown compare policy " + p.policy());
        };
    if (listField == null || !e.has(listField) || !a.has(listField)) {
      diffs.add(
          new Difference(
              "$." + (listField == null ? "?" : listField), "policy list field missing"));
      return diffs;
    }
    for (Iterator<String> it = e.fieldNames(); it.hasNext(); ) {
      String f = it.next();
      if (f.equals(listField)) continue;
      if (p.policy().equals("unit-multiset") && !p.exact().contains(f)) {
        diffs.add(new Difference("$." + f, "field not covered by policy (declare in exact)"));
        continue;
      }
      compare("$." + f, e.get(f), a.get(f), diffs);
    }
    for (Iterator<String> it = a.fieldNames(); it.hasNext(); ) {
      String f = it.next();
      if (!e.has(f)) diffs.add(new Difference("$." + f, "unexpected field"));
    }
    JsonNode el = e.get(listField), al = a.get(listField);
    if (el.size() != al.size()) {
      diffs.add(new Difference("$." + listField, "list size " + el.size() + " vs " + al.size()));
      return diffs;
    }
    if (p.policy().equals("unit-multiset")) {
      List<String> es = new ArrayList<>(), as = new ArrayList<>();
      for (JsonNode n : el) es.add(canonical(n));
      for (JsonNode n : al) as.add(canonical(n));
      java.util.Collections.sort(es);
      java.util.Collections.sort(as);
      if (!es.equals(as)) diffs.add(new Difference("$." + listField, "multiset differs"));
      return diffs;
    }
    String sf = scoreField(el.get(0));
    java.util.Map<String, Integer> actualPos = new java.util.HashMap<>();
    java.util.Map<String, java.math.BigDecimal> actualScore = new java.util.HashMap<>();
    for (int i = 0; i < al.size(); i++) {
      actualPos.put(identity(al.get(i)), i);
      actualScore.put(identity(al.get(i)), al.get(i).path(sf).decimalValue());
    }
    for (int i = 0; i < el.size(); i++) {
      String id = identity(el.get(i));
      Integer j = actualPos.get(id);
      if (j == null) {
        diffs.add(
            new Difference(
                "$." + listField + "[" + i + "]", "missing in actual: " + shorten(List.of(id))));
        continue;
      }
      if (Math.abs(i - j) > p.maxRankShift())
        diffs.add(
            new Difference(
                "$." + listField + "[" + i + "]",
                "rank shift " + Math.abs(i - j) + " > " + p.maxRankShift()));
      // 점수 허용 오차는 십진(JSON 표기)으로 잰다: 0.016-0.015는 double로 0.001을 넘지만 십진으로는 정확히 0.001이다
      java.math.BigDecimal ds =
          el.get(i).path(sf).decimalValue().subtract(actualScore.get(id)).abs();
      if (ds.compareTo(java.math.BigDecimal.valueOf(p.scoreAbsTolerance())) > 0)
        diffs.add(
            new Difference(
                "$." + listField + "[" + i + "]." + sf,
                "score diff " + ds + " > " + p.scoreAbsTolerance()));
    }
    return diffs;
  }

  private String firstScoredListField(JsonNode obj) {
    for (Iterator<String> it = obj.fieldNames(); it.hasNext(); ) {
      String f = it.next();
      if (obj.get(f).isArray() && isScoredList(obj.get(f))) return f;
    }
    return null;
  }

  static boolean isError(JsonNode result) {
    return result != null
        && (result.path("isError").asBoolean(false) || result.has("jsonrpcError"));
  }

  /**
   * 오류 종류: ok / validation(-32602·input validation) / unknown-tool / not-found(문서를 찾을 수 없음) /
   * provider(모델 호출) / other.
   */
  public static String errorKind(JsonNode result) {
    if (!isError(result)) return "ok";
    String text =
        result.has("jsonrpcError")
            ? result.get("jsonrpcError").toString()
            : result.path("content").path(0).path("text").asText("");
    String t = text.toLowerCase();
    // 순서가 중요하다: SDK 검증 문구 "required property 'x' not found"가 unknown-tool로 오분류됐다(첫 Java 실행) →
    // validation을 먼저 본다
    if (t.contains("validation") || t.contains("invalid arguments")) return "validation";
    if (t.matches("(?s).*tool\\s+\\S+\\s+not found.*")
        || t.contains("unknown tool")
        || t.contains("-32601")) return "unknown-tool";
    if (t.contains("-32602")) return "validation";
    if (text.contains("문서를 찾을 수 없음")) return "not-found";
    if (t.contains("embedding")) return "provider";
    return "other";
  }

  /** content[0].text가 JSON이면 파싱한 트리로 바꾼다(isError·문자열 본문은 그대로). 기준 테스트가 응답 본문을 단언할 때도 쓴다. */
  public static JsonNode unwrap(JsonNode result) {
    if (result == null || !result.isObject() || !result.has("content")) return result;
    // MCP 스펙에서 isError는 선택 필드(기본 false): TS는 생략하고 Java SDK는 false로 싣는다 — 와이어 의미가 같으므로 false는 생략과 같게
    // 본다
    if (result.has("isError") && !result.get("isError").asBoolean(false)) {
      var stripped = (com.fasterxml.jackson.databind.node.ObjectNode) result.deepCopy();
      stripped.remove("isError");
      result = stripped;
    }
    JsonNode content = result.get("content");
    if (!content.isArray()
        || content.size() != 1
        || !"text".equals(content.get(0).path("type").asText())) return result;
    String text = content.get(0).path("text").asText();
    JsonNode parsed;
    try {
      parsed = MAPPER.readTree(text);
    } catch (Exception e) {
      return result;
    }
    if (parsed == null || !(parsed.isObject() || parsed.isArray())) return result;
    var copy = result.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) copy)
        .set(
            "content",
            MAPPER
                .createArrayNode()
                .add(MAPPER.createObjectNode().put("type", "json").set("json", parsed)));
    return copy;
  }

  private void compare(String path, JsonNode e, JsonNode a, List<Difference> diffs) {
    if (e == null || a == null) {
      if (e != a) diffs.add(new Difference(path, e == null ? "unexpected" : "missing"));
      return;
    }
    if (e.isObject() && a.isObject()) {
      Set<String> keys = new LinkedHashSet<>();
      for (Iterator<String> it = e.fieldNames(); it.hasNext(); ) keys.add(it.next());
      for (Iterator<String> it = a.fieldNames(); it.hasNext(); ) keys.add(it.next());
      for (String k : keys) {
        JsonNode ev = e.get(k), av = a.get(k);
        if (ev == null) {
          diffs.add(new Difference(path + "." + k, "unexpected field"));
          continue;
        }
        if (av == null) {
          diffs.add(new Difference(path + "." + k, "missing field"));
          continue;
        }
        if (ev.isNumber() && av.isNumber() && policy.scoreFields().contains(k)) {
          if (!closeEnough(ev.doubleValue(), av.doubleValue()))
            diffs.add(
                new Difference(
                    path + "." + k,
                    "score " + ev + " vs " + av + " (rel > " + policy.scoreRelTolerance() + ")"));
        } else compare(path + "." + k, ev, av, diffs);
      }
      return;
    }
    if (e.isArray() && a.isArray()) {
      if (isScoredList(e)) compareScoredList(path, e, a, diffs);
      else {
        if (e.size() != a.size()) {
          diffs.add(new Difference(path, "array size " + e.size() + " vs " + a.size()));
          return;
        }
        for (int i = 0; i < e.size(); i++) compare(path + "[" + i + "]", e.get(i), a.get(i), diffs);
      }
      return;
    }
    if (e.isNumber() && a.isNumber()) {
      if (e.doubleValue() != a.doubleValue())
        diffs.add(new Difference(path, "number " + e + " vs " + a));
      return;
    }
    if (e.getNodeType() != a.getNodeType() || !e.equals(a))
      diffs.add(new Difference(path, "value " + brief(e) + " vs " + brief(a)));
  }

  private boolean closeEnough(double x, double y) {
    if (x == y) return true;
    double denom = Math.max(Math.abs(x), Double.MIN_NORMAL);
    return Math.abs(x - y) / denom <= policy.scoreRelTolerance();
  }

  private boolean isScoredList(JsonNode arr) {
    if (arr.isEmpty() || !arr.get(0).isObject()) return false;
    for (String f : policy.scoreFields()) if (arr.get(0).has(f)) return true;
    return false;
  }

  private String scoreField(JsonNode obj) {
    for (String f : policy.scoreFields()) if (obj.has(f)) return f;
    return null;
  }

  /** 동점군 안 원소의 신원 = 점수 필드를 뺀 객체 전체(정규화 JSON). documentId만으로는 같은 문서의 유닛 둘을 구분 못 한다(첫 TS 실행에서 발견). */
  private String identity(JsonNode obj) {
    var copy = obj.deepCopy();
    if (copy.isObject())
      for (String f : policy.scoreFields())
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove(f);
    return canonical(copy);
  }

  private static String canonical(JsonNode n) {
    if (n.isObject()) {
      java.util.TreeMap<String, String> m = new java.util.TreeMap<>();
      n.fields().forEachRemaining(e -> m.put(e.getKey(), canonical(e.getValue())));
      return m.toString();
    }
    if (n.isArray()) {
      List<String> parts = new ArrayList<>();
      n.forEach(x -> parts.add(canonical(x)));
      return parts.toString();
    }
    return n.isNumber() ? Double.toString(n.doubleValue()) : n.toString();
  }

  /**
   * 기대 목록을 동점군(연속 구간, 점수 차 ≤ tieEpsilon)으로 나누고, 실제 목록의 같은 위치 구간과 **다중집합**(점수 뺀 객체)으로 비교한다. 점수는 실제
   * 원소마다 그 군의 기대 점수와 상대 오차로 비교한다. 군 사이 순서는 그대로 — 하네스가 순위를 정리하지 않는다.
   */
  private void compareScoredList(String path, JsonNode e, JsonNode a, List<Difference> diffs) {
    if (e.size() != a.size()) {
      diffs.add(new Difference(path, "list size " + e.size() + " vs " + a.size()));
      return;
    }
    String sf = scoreField(e.get(0));
    int i = 0;
    while (i < e.size()) {
      int j = i + 1;
      double s0 = e.get(i).path(sf).asDouble();
      while (j < e.size() && Math.abs(e.get(j).path(sf).asDouble() - s0) <= policy.tieEpsilon())
        j++;
      List<String> expectedIds = new ArrayList<>();
      List<String> actualIds = new ArrayList<>();
      for (int k = i; k < j; k++) {
        expectedIds.add(identity(e.get(k)));
        actualIds.add(identity(a.get(k)));
      }
      List<String> es = new ArrayList<>(expectedIds), as = new ArrayList<>(actualIds);
      java.util.Collections.sort(es);
      java.util.Collections.sort(as);
      if (!es.equals(as))
        diffs.add(
            new Difference(
                path + "[" + i + ".." + (j - 1) + "]",
                "tie group members differ: expected "
                    + shorten(expectedIds)
                    + " actual "
                    + shorten(actualIds)));
      for (int k = i; k < j; k++) {
        double sa = a.get(k).path(sf).asDouble();
        if (!closeEnough(s0, sa))
          diffs.add(
              new Difference(
                  path + "[" + k + "]." + sf,
                  "score " + s0 + " vs " + sa + " (rel > " + policy.scoreRelTolerance() + ")"));
      }
      i = j;
    }
  }

  private static String shorten(List<String> ids) {
    List<String> out = new ArrayList<>();
    for (String s : ids) out.add(s.length() > 60 ? s.substring(0, 57) + "..." : s);
    return out.toString();
  }

  private static String brief(JsonNode n) {
    String s = n.toString();
    return s.length() > 80 ? s.substring(0, 77) + "..." : s;
  }
}

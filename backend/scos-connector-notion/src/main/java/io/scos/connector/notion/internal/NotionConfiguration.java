package io.scos.connector.notion.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.SourceConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Notion 소스 설정: 기존 schemaVersion 1과 시간 정책을 추가한 roots용 2. 빈 객체면 검색 모드(공유된 페이지 전체를 검색으로),
 * `rootPageIds`(빈 값이 아닌 고유 문자열 1개 이상)가 있으면 roots 모드(설정한 루트 페이지 트리를 작업 큐로 걷는다 — 운영 설정)다.
 * `discoverSharedPages`(선택, 불리언)를 켜면 트리 순회 뒤 검색으로 공유된 다른 페이지도 본다. v2의 증분 주기·겹침은
 * discoverSharedPages=false인 재조정 경로에만 적용한다. 그 밖의 키·모양은 거부한다.
 */
public sealed interface NotionConfiguration {
  String INVALID = "Invalid Notion source configuration";

  /** 검색 모드: 체크포인트 v1·v2. */
  record Search() implements NotionConfiguration {}

  /** roots 설정: 재조정은 v4, discoverSharedPages=true인 기존 경로는 v3 작업 큐. */
  public record Roots(
      List<String> rootPageIds,
      boolean discoverSharedPages,
      Duration incrementalInterval,
      Duration overlap)
      implements NotionConfiguration {
    static final Duration DEFAULT_INCREMENTAL_INTERVAL = Duration.ofMinutes(15);
    static final Duration DEFAULT_OVERLAP = Duration.ofMinutes(30);

    public Roots {
      rootPageIds = List.copyOf(rootPageIds);
    }
  }

  static NotionConfiguration read(ObjectMapper json, SourceConfig config) {
    JsonNode value;
    try {
      value = json.readTree(config.valueJson());
    } catch (JacksonException e) {
      throw invalid();
    }
    if ((config.schemaVersion() != 1 && config.schemaVersion() != 2) || !value.isObject())
      throw invalid();
    if (value.isEmpty() && config.schemaVersion() == 1) return new Search();
    Set<String> keys = new HashSet<>(value.propertyNames());
    keys.removeAll(Set.of("rootPageIds", "discoverSharedPages"));
    if (config.schemaVersion() == 2)
      keys.removeAll(Set.of("incrementalIntervalSeconds", "overlapSeconds"));
    JsonNode roots = value.get("rootPageIds");
    JsonNode discover = value.get("discoverSharedPages");
    if (!keys.isEmpty()
        || roots == null
        || !roots.isArray()
        || roots.isEmpty()
        || (discover != null && !discover.isBoolean())) throw invalid();
    List<String> ids = new ArrayList<>();
    for (JsonNode root : roots) {
      if (!NotionObjects.nonEmptyString(root)) throw invalid();
      ids.add(root.asString());
    }
    if (new HashSet<>(ids).size() != ids.size()) throw invalid();
    if (discover != null
        && discover.asBoolean()
        && (value.has("incrementalIntervalSeconds") || value.has("overlapSeconds")))
      throw invalid(
          "incrementalIntervalSeconds and overlapSeconds require discoverSharedPages=false");
    var interval = seconds(value, "incrementalIntervalSeconds", Roots.DEFAULT_INCREMENTAL_INTERVAL);
    var overlap = seconds(value, "overlapSeconds", Roots.DEFAULT_OVERLAP);
    if (overlap.compareTo(interval) < 0)
      throw invalid("overlapSeconds must be greater than or equal to incrementalIntervalSeconds");
    return new Roots(ids, discover != null && discover.asBoolean(), interval, overlap);
  }

  private static Duration seconds(JsonNode value, String key, Duration fallback) {
    if (!value.has(key)) return fallback;
    var seconds = value.get(key);
    if (!seconds.isIntegralNumber() || !seconds.canConvertToInt() || seconds.asInt() <= 0)
      throw invalid(key + " must be a positive integer number of seconds");
    return Duration.ofSeconds(seconds.asInt());
  }

  private static CallFailureException invalid(String reason) {
    return new CallFailureException(
        "notion.configuration",
        CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, INVALID + ": " + reason));
  }

  private static CallFailureException invalid() {
    return new CallFailureException(
        "notion.configuration", CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, INVALID));
  }
}

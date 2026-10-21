package io.scos.connector.notion.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.SourceConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Notion 소스 설정(TS readNotionConfiguration, notion-root-traversal.ts): schemaVersion 1. 빈 객체면 검색
 * 모드(공유된 페이지 전체를 검색으로), `rootPageIds`(빈 값이 아닌 고유 문자열 1개 이상)가 있으면 roots 모드(설정한 루트 페이지 트리를 작업 큐로 걷는다
 * — 운영 설정)다. `discoverSharedPages`(선택, 불리언)를 켜면 트리 순회 뒤 검색으로 공유된 다른 페이지도 본다. 그 밖의 키·모양은 거부하고 메시지는
 * TS와 같다.
 */
public sealed interface NotionConfiguration {
  String INVALID = "Invalid Notion source configuration";

  /** 검색 모드: 체크포인트 v1·v2. */
  record Search() implements NotionConfiguration {}

  /** roots 모드: 체크포인트 v3 작업 큐. */
  public record Roots(List<String> rootPageIds, boolean discoverSharedPages)
      implements NotionConfiguration {
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
    if (config.schemaVersion() != 1 || !value.isObject()) throw invalid();
    if (value.isEmpty()) return new Search();
    Set<String> keys = new HashSet<>(value.propertyNames());
    keys.removeAll(Set.of("rootPageIds", "discoverSharedPages"));
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
    return new Roots(ids, discover != null && discover.asBoolean());
  }

  private static CallFailureException invalid() {
    return new CallFailureException(
        "notion.configuration", CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, INVALID));
  }
}

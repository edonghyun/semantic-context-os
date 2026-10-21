package io.scos.connector.notion.internal;

import tools.jackson.databind.JsonNode;

/**
 * Notion 객체 모양 검사(TS isNotionPage·isNotionBlock·isNotionDatabase·isNotionDataSource): object 이름, 빈
 * 값이 아닌 문자열 id, 문자열 created_time·last_edited_time, 불리언 in_trash. 블록은 빈 값이 아닌 type과 불리언
 * has_children, database는 {id(빈 값 아님), name(문자열)} 배열 data_sources를 더 본다. 나머지 필드는 보지 않고 원문 그대로 둔다.
 */
final class NotionObjects {
  static final String PAGE = "page";
  static final String DATA_SOURCE = "data_source";

  private NotionObjects() {}

  static boolean isPage(JsonNode value) {
    return timestamped(value, PAGE);
  }

  static boolean isBlock(JsonNode value) {
    return timestamped(value, "block")
        && nonEmptyString(value.get("type"))
        && isBoolean(value.get("has_children"));
  }

  static boolean isDatabase(JsonNode value) {
    if (!timestamped(value, "database")) return false;
    JsonNode dataSources = value.get("data_sources");
    if (dataSources == null || !dataSources.isArray()) return false;
    for (JsonNode dataSource : dataSources)
      if (!dataSource.isObject()
          || !nonEmptyString(dataSource.get("id"))
          || !isString(dataSource.get("name"))) return false;
    return true;
  }

  static boolean isDataSource(JsonNode value) {
    return timestamped(value, DATA_SOURCE);
  }

  /** data source 조회 결과 한 줄: 행 페이지 또는 중첩 data source. */
  static boolean isDataSourceEntry(JsonNode value) {
    return isPage(value) || isDataSource(value);
  }

  static boolean nonEmptyString(JsonNode value) {
    return isString(value) && !value.asString().isEmpty();
  }

  private static boolean timestamped(JsonNode value, String object) {
    return value != null
        && value.isObject()
        && isString(value.get("object"))
        && value.get("object").asString().equals(object)
        && nonEmptyString(value.get("id"))
        && isString(value.get("created_time"))
        && isString(value.get("last_edited_time"))
        && isBoolean(value.get("in_trash"));
  }

  private static boolean isString(JsonNode value) {
    return value != null && value.isString();
  }

  private static boolean isBoolean(JsonNode value) {
    return value != null && value.isBoolean();
  }
}

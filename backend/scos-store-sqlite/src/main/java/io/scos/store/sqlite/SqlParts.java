package io.scos.store.sqlite;

import io.scos.spi.access.AccessKey;
import io.scos.spi.store.AccessScope;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import tools.jackson.databind.json.JsonMapper;

/** SQLite 목록 바인딩과 ACL 조건은 같은 JSON 표현을 쓴다. */
final class SqlParts {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SqlParts() {}

  static List<String> strings(String json) {
    return List.of(JSON.readValue(json, String[].class));
  }

  static String json(Collection<?> values) {
    return JSON.writeValueAsString(values);
  }

  /** 접근 범위가 제한이면 문서 ACL 조건(허용 키와 겹치는 ok·unsupported 문서)을 덧붙인다. 빈 키는 아무것도 맞지 않는다. */
  static String aclFilter(String documentColumn) {
    return " AND EXISTS (SELECT 1 FROM document_acl a WHERE a.space_id = :space AND a.document_id = "
        + documentColumn
        + " AND a.status IN ('ok','unsupported')"
        + " AND EXISTS (SELECT 1 FROM json_each(a.keys) k WHERE k.value IN (SELECT value FROM json_each(:keys))))";
  }

  static StatementSpec bindKeys(StatementSpec statement, AccessScope access) {
    if (access instanceof AccessScope.Restricted restricted)
      return statement.param(
          "keys", json(restricted.keys().stream().map(AccessKey::toString).sorted().toList()));
    return statement;
  }
}

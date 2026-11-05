package io.scos.connector.jira.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import io.scos.spi.access.AccessKey;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceInstanceId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** 최종 사용자 집합과 그 내용 키는 분리해서 만들 수 없다. 기존 그룹의 멤버를 새 grant에 재사용하지 않는다. */
final class JiraGrantGroups {
  static final class Grant {
    private final AccessKey key;
    private final GroupSnapshot snapshot;

    private Grant(SourceInstanceId source, String id, Set<String> users, Instant at) {
      key = new AccessKey(AccessKey.Kind.GROUP, source, id);
      snapshot = GroupSnapshot.synthetic(id, users, at);
    }

    AccessKey key() {
      return key;
    }

    GroupSnapshot snapshot() {
      return snapshot;
    }
  }

  private final SourceInstanceId source;

  JiraGrantGroups(SourceInstanceId source) {
    this.source = source;
  }

  Grant project(String projectId, Collection<String> users, Instant at) {
    return bind("project", projectId, users, at);
  }

  Grant level(String levelId, Collection<String> users, Instant at) {
    return bind("level", levelId, users, at);
  }

  private Grant bind(String namespace, String id, Collection<String> users, Instant at) {
    Set<String> members = Set.copyOf(users);
    for (String member : members) {
      if (!StandardCharsets.UTF_8.newEncoder().canEncode(member))
        throw new IllegalArgumentException("Invalid Jira accountId Unicode");
    }
    String canonical =
        members.stream().sorted().map(JsJson::string).collect(Collectors.joining(",", "[", "]"));
    return new Grant(
        source, "~" + namespace + ":" + id + ":" + Text.sha256Hex(canonical), members, at);
  }
}

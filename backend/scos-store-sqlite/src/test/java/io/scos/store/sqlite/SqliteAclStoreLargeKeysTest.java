package io.scos.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.store.SpaceId;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SQLite ACL 저장소는 키가 많은 문서도 저장한다")
class SqliteAclStoreLargeKeysTest {
  @TempDir Path dir;

  @Test
  @DisplayName("키 1500개짜리 ACL을 그대로 돌려준다(함수 인자 한도 1000을 넘는다)")
  void storesMoreKeysThanFunctionArgumentLimit() {
    // given
    var store = SqliteIndex.open(dir.resolve("index.sqlite")).acl();
    var space = new SpaceId("a");
    Set<AccessKey> keys =
        IntStream.range(0, 1500)
            .mapToObj(i -> AccessKey.parse("user:slack-a:U" + i))
            .collect(Collectors.toUnmodifiableSet());
    var acl =
        new DocumentAcl("doc", DocumentAcl.Status.OK, keys, Instant.parse("2026-10-26T00:00:00Z"));

    // when
    store.put(space, List.of(acl));

    // then
    assertThat(store.get(space, List.of("doc"))).containsEntry("doc", acl);
  }
}

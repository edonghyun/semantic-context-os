package io.scos.spi.connector;

import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceIdentityRecord;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 외부 식별자별 원본 ACL과 그룹·신원 관측값. 빈 컬렉션은 이 페이지에 해당 관측이 없음을 뜻한다. */
public record AclPage(
    Map<String, RawAcl> acls,
    List<GroupSnapshot> groups,
    List<SourceIdentityRecord> identities,
    Set<String> aclUnreadable) {
  public AclPage(
      Map<String, RawAcl> acls, List<GroupSnapshot> groups, List<SourceIdentityRecord> identities) {
    this(acls, groups, identities, Set.of());
  }

  public AclPage {
    aclUnreadable = Set.copyOf(aclUnreadable);
    acls = Map.copyOf(acls);
    groups = List.copyOf(groups);
    identities = List.copyOf(identities);
  }
}

package io.scos.collection.internal;

import io.scos.engine.support.PureFunctions;
import io.scos.spi.connector.AclPage;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory.Snapshot;
import io.scos.spi.source.SourceIdentityRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 양 저장 구현이 공유하는 시각 고수위·전체성·회수 정책. 저장이나 트랜잭션은 알지 못한다. */
@PureFunctions
public final class AccessMergePolicy {
  private AccessMergePolicy() {}

  /** 상충은 run을 폐기할 때까지 유지한다. 각 페이지의 전체 그룹 관측을 시간순으로 접는다. */
  public record Observations(Snapshot snapshot, boolean conflicted) {
    public static Observations empty() {
      return new Observations(Snapshot.empty(), false);
    }

    public Observations observe(AclPage page) {
      var groups = new HashMap<>(snapshot.groups());
      var identities = new HashMap<>(snapshot.identities());
      boolean conflict = conflicted;
      for (GroupSnapshot input : page.groups()) {
        var incoming = input.withObservation(input.members(), micros(input.observedAt()));
        var old = groups.get(incoming.groupId());
        if (old != null && incoming.observedAt().isBefore(old.observedAt())) continue;
        if (old != null
            && incoming.observedAt().equals(old.observedAt())
            && !old.equals(incoming)) {
          incoming = restrict(old, incoming);
          conflict = true;
        }
        groups.put(incoming.groupId(), incoming);
      }
      for (SourceIdentityRecord input : page.identities()) {
        var incoming = at(input, micros(input.observedAt()));
        var old = identities.get(incoming.sourceUserId());
        if (old != null && incoming.observedAt().isBefore(old.observedAt())) continue;
        if (old != null
            && incoming.observedAt().equals(old.observedAt())
            && !old.equals(incoming)) {
          incoming = restrict(old, incoming);
          conflict = true;
        }
        identities.put(incoming.sourceUserId(), incoming);
      }
      return new Observations(new Snapshot(identities, groups), conflict);
    }
  }

  public record Merged(Snapshot snapshot, AccessMerge mode) {}

  public static Merged merge(AccessMerge mode, Snapshot current, Snapshot observed, Instant at) {
    validateGroups(observed, mode == AccessMerge.COMPLETE);
    Set<String> invalidGroups =
        observed.groups().values().stream()
            .filter(g -> g.members().stream().anyMatch(m -> invalidReference(g, m, observed)))
            .map(GroupSnapshot::groupId)
            .collect(Collectors.toSet());
    return new Merged(
        new Snapshot(
            mergeIdentities(mode, current, observed),
            mergeGroups(mode, current, observed, at, invalidGroups)),
        invalidGroups.isEmpty() ? mode : AccessMerge.INCOMPLETE);
  }

  private static Map<String, SourceIdentityRecord> mergeIdentities(
      AccessMerge mode, Snapshot current, Snapshot observed) {
    var identities = new HashMap<>(current.identities());
    for (SourceIdentityRecord incoming : observed.identities().values()) {
      var old = identities.get(incoming.sourceUserId());
      if (old != null && incoming.observedAt().isBefore(old.observedAt())) continue;
      if (old == null && mode == AccessMerge.INCOMPLETE) continue;
      var next =
          old != null
                  && (mode == AccessMerge.INCOMPLETE
                      || incoming.observedAt().equals(old.observedAt()))
              ? restrict(old, incoming)
              : incoming;
      identities.put(incoming.sourceUserId(), next);
    }
    return identities;
  }

  private static Map<String, GroupSnapshot> mergeGroups(
      AccessMerge mode,
      Snapshot current,
      Snapshot observed,
      Instant at,
      Set<String> invalidGroups) {
    var groups = new HashMap<>(current.groups());
    for (GroupSnapshot incoming : observed.groups().values()) {
      var old = groups.get(incoming.groupId());
      if (old != null && incoming.observedAt().isBefore(old.observedAt())) continue;
      boolean incomplete =
          mode == AccessMerge.INCOMPLETE || invalidGroups.contains(incoming.groupId());
      if (old == null && incomplete) continue;
      incoming = withoutInvalidReferences(incoming, observed);
      var next =
          old != null && (incomplete || incoming.observedAt().equals(old.observedAt()))
              ? restrict(old, incoming)
              : incoming;
      groups.put(incoming.groupId(), next);
    }
    if (mode == AccessMerge.COMPLETE) {
      for (GroupSnapshot old : current.groups().values())
        if (!observed.groups().containsKey(old.groupId()) && !micros(at).isBefore(old.observedAt()))
          groups.put(old.groupId(), old.withObservation(Set.of(), micros(at)));
    }
    return groups;
  }

  /** 같은 key·observedAt의 상충 신원을 제한한다. 미완료 병합은 incoming 시각을 고수위로 쓴다. */
  public static SourceIdentityRecord restrict(
      SourceIdentityRecord old, SourceIdentityRecord incoming) {
    return new SourceIdentityRecord(
        incoming.sourceUserId(),
        old.email().equals(incoming.email()) ? old.email() : Optional.empty(),
        old.emailVerified() && incoming.emailVerified(),
        old.guest() || incoming.guest(),
        old.deleted() || incoming.deleted(),
        incoming.observedAt());
  }

  /** 같은 그룹의 구성원을 교집합으로 제한하며 incoming의 관측 시각을 보존한다. */
  public static GroupSnapshot restrict(GroupSnapshot old, GroupSnapshot incoming) {
    Set<String> members = new HashSet<>(old.members());
    members.retainAll(incoming.members());
    return incoming.withObservation(members, incoming.observedAt());
  }

  private static SourceIdentityRecord at(SourceIdentityRecord input, Instant at) {
    return new SourceIdentityRecord(
        input.sourceUserId(),
        input.email(),
        input.emailVerified(),
        input.guest(),
        input.deleted(),
        at);
  }

  private static Instant micros(Instant at) {
    return at.truncatedTo(ChronoUnit.MICROS);
  }

  private static void validateGroups(Snapshot observed, boolean complete) {
    for (GroupSnapshot group : observed.groups().values()) {
      for (String member : group.members()) {
        if (invalidReference(group, member, observed)) continue;
        if (member.startsWith("~")) {
          if (complete && !observed.groups().containsKey(member))
            throw new IncompleteSnapshotException();
        } else if (complete && !observed.identities().containsKey(member)) {
          throw new IncompleteSnapshotException();
        }
      }
    }
  }

  private static boolean invalidReference(GroupSnapshot group, String member, Snapshot observed) {
    return member.startsWith("~")
        ? group.groupId().startsWith("~")
        : observed.groups().containsKey(member);
  }

  private static GroupSnapshot withoutInvalidReferences(GroupSnapshot group, Snapshot observed) {
    return group.withObservation(
        group.members().stream()
            .filter(member -> !invalidReference(group, member, observed))
            .collect(Collectors.toSet()),
        group.observedAt());
  }

  /** COMPLETE의 결정적인 전체성 위반만 fallback 대상이다. */
  public static final class IncompleteSnapshotException extends RuntimeException {
    public IncompleteSnapshotException() {
      super("Access snapshot is missing a member identity or synthetic group");
    }
  }
}

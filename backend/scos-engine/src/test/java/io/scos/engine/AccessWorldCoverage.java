package io.scos.engine;

import io.scos.engine.AccessPropertyScenario.Requester;
import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessKey;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 속성 세계에서 회귀를 구별하는 사용자·문서·질의의 증인을 찾는다. */
record AccessWorldCoverage(RandomAccessWorld world) {
  List<String> groupOnlyPairs() {
    return requesters()
        .flatMap(
            requester ->
                gainedPairs(
                    requester,
                    requester.keys().stream()
                        .filter(key -> key.kind() != AccessKey.Kind.GROUP)
                        .collect(Collectors.toUnmodifiableSet())))
        .toList();
  }

  List<String> expandedOnlyPairs() {
    var directory = world.directory();
    var directOnly =
        new AccessDirectory(
            directory.users(),
            directory.identities(),
            directory.links(),
            directory.groups().stream()
                .map(
                    group ->
                        new AccessDirectory.Group(
                            group.instance(),
                            group.id(),
                            group.members().stream()
                                .filter(member -> !member.startsWith("~"))
                                .collect(Collectors.toUnmodifiableSet())))
                .toList(),
            directory.scopes());
    return requesters()
        .flatMap(
            requester ->
                gainedPairs(
                    requester,
                    new ReferenceAccessKeys().keys(directOnly, requester.user(), world.domains())))
        .toList();
  }

  List<String> competingQueries() {
    return requesters()
        .flatMap(
            requester ->
                RandomAccessWorld.QUERIES.stream()
                    .filter(query -> hasMatch(requester, query, true))
                    .filter(query -> hasMatch(requester, query, false))
                    .map(query -> requester.user().id() + "/" + query))
        .toList();
  }

  private boolean hasMatch(Requester requester, String query, boolean visible) {
    return world.corpus().stream()
        .anyMatch(
            unit ->
                unit.content().contains(query)
                    && requester.visibleDocuments().contains(unit.documentId()) == visible);
  }

  private Stream<String> gainedPairs(Requester requester, Set<AccessKey> reducedKeys) {
    return world.acls().values().stream()
        .filter(acl -> acl.visibleTo(requester.keys()) && !acl.visibleTo(reducedKeys))
        .map(acl -> requester.user().id() + "/" + acl.documentId());
  }

  private Stream<Requester> requesters() {
    return world.directory().users().stream().map(user -> new Requester(world, user));
  }
}

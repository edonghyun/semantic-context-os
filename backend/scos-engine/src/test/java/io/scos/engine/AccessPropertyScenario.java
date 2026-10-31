package io.scos.engine;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.RecordedSourceFreshness;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.support.TestExecutors;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 난수 세계의 한 요청자와 독립적으로 계산한 가시 코퍼스를 조립한다. */
final class AccessPropertyScenario {
  private static final double[] QUERY_VECTOR = {1, 0};
  private final Requester requester;
  private final AccessAdapters adapters;

  AccessPropertyScenario(Requester requester) {
    this.requester = requester;
    adapters = AccessAdapters.memory();
    adapters.load(SpaceId.LEGACY, requester.world().corpus());
    adapters.seed().accept(requester.world().directory());
    adapters.acls().put(SpaceId.LEGACY, List.copyOf(requester.world().acls().values()));
  }

  private ContextEngineBuilder builder(AccessAdapters target) {
    return ContextEngine.builder()
        .units(target.units())
        .keywords(target.keywords())
        .vectors(target.vectors())
        .embedder(
            FixedEmbedder.byKeyword(
                RandomAccessWorld.QUERIES.getFirst(), QUERY_VECTOR, new double[] {0, 1}))
        .clock(Clock.fixed(RandomAccessWorld.OBSERVED, ZoneOffset.UTC))
        .lexicalExecutor(TestExecutors.CALLER_THREAD);
  }

  ContextEngine restricted() {
    return builder(adapters)
        .access(adapters.identities(), new RecordedSourceFreshness(), requester.world().domains())
        .build();
  }

  ContextEngine visibleOnly() {
    var visible = AccessAdapters.memory();
    visible.load(
        SpaceId.LEGACY,
        requester.world().corpus().stream()
            .filter(unit -> requester.visibleDocuments().contains(unit.documentId()))
            .toList());
    return builder(visible).unrestricted(visible.maintenance()).build();
  }

  static final class Requester {
    private final RandomAccessWorld world;
    private final AccessDirectory.User user;
    private final Set<AccessKey> keys;
    private final Set<String> visibleDocuments;

    Requester(RandomAccessWorld world, AccessDirectory.User user) {
      this.world = world;
      this.user = user;
      keys = new ReferenceAccessKeys().keys(world.directory(), user, world.domains());
      visibleDocuments =
          world.acls().values().stream()
              .filter(acl -> acl.visibleTo(keys))
              .map(acl -> acl.documentId())
              .collect(Collectors.toUnmodifiableSet());
    }

    RandomAccessWorld world() {
      return world;
    }

    AccessDirectory.User user() {
      return user;
    }

    Principal principal() {
      return new Principal(user.id());
    }

    Set<AccessKey> keys() {
      return keys;
    }

    Set<String> visibleDocuments() {
      return visibleDocuments;
    }

    AccessPropertyScenario scenario() {
      return new AccessPropertyScenario(this);
    }

    String counterexample(String document) {
      return this + ", document=" + document + ", acl=" + world.acls().get(document);
    }

    @Override
    public String toString() {
      return "seed=" + world.seed() + ", user=" + user + ", keys=" + keys();
    }
  }
}

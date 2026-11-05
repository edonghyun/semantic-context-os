package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.projection.MemoryProjection;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.slack.internal.FakeSlackServer;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.source.SourceInstanceId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Slack 첫 보충은 과거 메시지의 ACL을 API 없이 계산한다(접근 설계 §5.5)")
class SlackAclBackfillTest {
  private BackfillScenario scenario;

  @BeforeEach
  void setUp() throws IOException {
    scenario = new BackfillScenario();
  }

  @AfterEach
  void close() {
    scenario.close();
  }

  @Test
  @DisplayName("77,365개 active 메시지를 모두 보충하고 history와 replies를 호출하지 않는다")
  void allHistoricalMessagesAreFilledWithoutHistoryCalls() {
    // given
    scenario.givenHistoricalMessages(77_365);
    // when
    scenario.collect();
    // then
    assertThat(scenario.filledAcls())
        .hasSize(77_365)
        .allSatisfy(
            acl ->
                assertThat(acl)
                    .extracting(RawAcl.Observed::status, RawAcl.Observed::keys)
                    .containsExactly(Status.OK, Set.of(AccessKey.parse("group:slack-a:C1"))));
    assertThat(scenario.bodyRequests()).isEmpty();
  }

  private static final class BackfillScenario implements AutoCloseable {
    private static final SourceInstanceId SOURCE = new SourceInstanceId("slack-a");
    private final MemoryProjection memory = new MemoryProjection();
    private final MemoryRunStore runs = MemoryRunStore.create();
    private final FakeSlackServer server;
    private final CollectionRuntime runtime;
    private final List<String> ids = new ArrayList<>();

    BackfillScenario() throws IOException {
      server =
          new FakeSlackServer(
              Path.of(
                  "scos-collection/src/test/resources/io/scos/collection/internal/slack-access-dataset.json"));
      server.clearChannels();
      memory.sources.save(
          SourceInstances.of(
              SOURCE,
              "slack",
              SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP,
              "env:SLACK_LOCAL_FAKE",
              memory.clock.instant()));
      runtime =
          MemoryCollectionRuntime.over(memory.clock, memory.sources, memory.claims, runs)
              .rawItems(memory.rawItems)
              .outbox(memory.outbox)
              .adapter(SlackAssembly.adapter(server.baseUrl(), server.token(), memory.clock))
              .collectAccess(true)
              .build();
    }

    void givenHistoricalMessages(int count) {
      for (int i = 0; i < count; i++) {
        String ts = "1760400000." + String.format(Locale.ROOT, "%06d", i);
        String id = "C1:" + ts;
        ids.add(id);
        memory.rawItems.apply(
            SOURCE,
            RawItems.item(id)
                .version(ts)
                .metadata("{\"channelId\":\"C1\",\"messageTs\":\"" + ts + "\"}")
                .inlineJson(
                    "{\"text\":\"Historical release documentation for the workspace maintainers.\"}")
                .upsert());
      }
      if (memory.rawItems.count(SOURCE) != count || !filledAcls().isEmpty())
        throw new IllegalStateException("given 준비 실패: ACL이 없는 active 메시지 집합이어야 한다");
    }

    CollectionRun collect() {
      runtime.collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
      return runs.latest(SOURCE).orElseThrow();
    }

    List<RawAcl.Observed> filledAcls() {
      return List.copyOf(memory.rawAcls.get(SOURCE, ids).values());
    }

    List<String> bodyRequests() {
      return server.requests().stream()
          .filter(
              path ->
                  path.startsWith("/api/conversations.history")
                      || path.startsWith("/api/conversations.replies"))
          .toList();
    }

    @Override
    public void close() {
      server.close();
    }
  }
}

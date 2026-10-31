package io.scos.connector.notion.internal;

import io.scos.connector.notion.NotionAssembly;
import io.scos.spi.connector.CollectionPage;
import io.scos.support.FakeHttpServer.Response;
import java.net.HttpURLConnection;
import java.util.Map;
import java.util.Optional;

/** 실제 부재 조회와 복구 커밋 도중 가짜 시계가 due를 넘는 연결. */
final class NotionVerificationInterleaveScenario implements AutoCloseable {
  private static final String FIRST_BLOCK = "restoring-first";
  private static final String SECOND_BLOCK = "restoring-second";
  private final NotionAbsenceScenario context = new NotionAbsenceScenario();
  private long searchesAtRecoveryStart;
  private boolean incrementalRanBeforeRecoveryContinued;

  NotionVerificationInterleaveScenario() {
    var data = context.data;
    var page =
        NotionScopeScenario.page(
            NotionAbsenceScenario.CANDIDATE, "page_id", NotionRefreshScenario.PAGE);
    data.raw.apply(NotionAssembly.SOURCE, context.mutations.page(page));
    data.server.respond(
        "GET",
        "/v1/pages/" + NotionAbsenceScenario.CANDIDATE,
        new Response(HttpURLConnection.HTTP_OK, page.toString(), Map.of()));
    data.emptyBodyAvailable();
    data.bodyPage(
        NotionAbsenceScenario.CANDIDATE,
        Optional.empty(),
        Optional.of("restore-next"),
        data.block(FIRST_BLOCK));
    data.bodyPage(
        NotionAbsenceScenario.CANDIDATE,
        Optional.of("restore-next"),
        Optional.empty(),
        data.block(SECOND_BLOCK));
    data.server.respond(
        "POST",
        "/v1/search",
        new Response(HttpURLConnection.HTTP_OK, NotionApiClientTest.EMPTY_SEARCH, Map.of()));
    data.claims.renew(
        data.claim, NotionReconciliationScenario.RENEWED_LEASE_TTL, data.clock.instant());
  }

  void collect() {
    new NotionReconciliation(
            NotionRefreshScenario.ROOTS,
            context.data.state,
            new NotionReconciliation.Dependencies(
                context.client, context.input, context.data.clock, context.data.server.token()))
        .collect(this::commit);
  }

  private void commit(CollectionPage page) {
    context.data.commit(page);
    if (page.mutations().stream()
        .anyMatch(mutation -> mutation.externalId().equals("block:" + FIRST_BLOCK))) {
      searchesAtRecoveryStart = activeSearchRequests();
      context.data.clock.advance(NotionReconciliationScenario.ELAPSED_UNTIL_DUE);
    }
    if (page.mutations().stream()
        .anyMatch(mutation -> mutation.externalId().equals("block:" + SECOND_BLOCK)))
      incrementalRanBeforeRecoveryContinued = activeSearchRequests() > searchesAtRecoveryStart;
  }

  private long activeSearchRequests() {
    return context.data.server.requests().stream()
        .filter(request -> request.path().equals("/v1/search"))
        .filter(
            request ->
                !NotionFixtures.JSON.readTree(request.body()).at("/filter/in_trash").asBoolean())
        .count();
  }

  boolean incrementalRanBeforeRecoveryContinued() {
    return incrementalRanBeforeRecoveryContinued;
  }

  @Override
  public void close() {
    context.close();
  }
}

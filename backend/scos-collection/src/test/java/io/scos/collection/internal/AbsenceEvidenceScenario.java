package io.scos.collection.internal;

import io.scos.spi.connector.SourceOrder;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 검증 token이 소유자의 저장 증거 변화에 묶이는 상황을 만든다. */
final class AbsenceEvidenceScenario {
  enum Change {
    ITEM_VERSION,
    ITEM_ORDER,
    OWNER_VERSION,
    OWNER_ORDER,
    OWNER_OBSERVED,
    COMPLETED_VERSION,
    COMPLETED_ORDER,
    COMPLETED_RUN,
    COMPLETED_BLOCKS,
    PENDING_VERSION,
    PENDING_RUN,
    PENDING_BLOCKS
  }

  static final RunId OLD_RUN = new RunId("old-body-run");
  static final RunId OTHER_RUN = new RunId("other-body-run");
  static final SourceOrder NEXT_ORDER = new SourceOrder("notion.page.v1", "0002");
  static final String BLOCK = "block:owned";
  static final String OWNER = "seen";
  final AbsenceVerificationScenario data;
  final Change change;

  AbsenceEvidenceScenario(AbsenceVerificationScenario data, Change change) {
    this.data = data;
    this.change = change;
    data.blockHasKnownOwner();
    if (change == Change.OWNER_OBSERVED) data.blockHasDeletedOtherOwner();
    if (change.name().startsWith("PENDING")) savePending("v1", OLD_RUN, Set.of(BLOCK));
  }

  String token() {
    return data.candidates().token();
  }

  void changeEvidence() {
    switch (change) {
      case ITEM_VERSION ->
          data.ports
              .rawItems()
              .apply(
                  data.source.id(),
                  RawItems.item("outside")
                      .resourceType(AbsenceVerificationScenario.PAGE_TYPE)
                      .version("v2")
                      .upsert());
      case ITEM_ORDER -> restoreWithOrder("outside");
      case OWNER_VERSION ->
          data.ports
              .rawItems()
              .apply(
                  data.source.id(),
                  RawItems.item(OWNER)
                      .resourceType(AbsenceVerificationScenario.PAGE_TYPE)
                      .version("v2")
                      .upsert());
      case OWNER_ORDER -> restoreWithOrder(OWNER);
      case OWNER_OBSERVED ->
          data.ports
              .runs()
              .observe(
                  AbsenceVerificationScenario.RUN,
                  List.of(AbsenceVerificationScenario.DELETED_OWNER));
      case COMPLETED_VERSION ->
          saveBody("v2", BodyCommitScenario.ORDER, Optional.empty(), Set.of(BLOCK));
      case COMPLETED_ORDER -> saveBody("v1", NEXT_ORDER, Optional.empty(), Set.of(BLOCK));
      case COMPLETED_RUN ->
          saveBody("v1", BodyCommitScenario.ORDER, Optional.of(OLD_RUN), Set.of(BLOCK));
      case COMPLETED_BLOCKS ->
          saveBody("v1", BodyCommitScenario.ORDER, Optional.empty(), Set.of(BLOCK, "block:extra"));
      case PENDING_VERSION -> savePending("v2", OLD_RUN, Set.of(BLOCK));
      case PENDING_RUN -> savePending("v1", OTHER_RUN, Set.of(BLOCK));
      case PENDING_BLOCKS -> savePending("v1", OLD_RUN, Set.of(BLOCK, "block:extra"));
    }
  }

  private void restoreWithOrder(String id) {
    data.ports.rawItems().tombstoneSelected(data.source.id(), Set.of(id), data.clock.instant());
    data.ports
        .rawItems()
        .apply(
            data.source.id(),
            RawItems.item(id)
                .resourceType(AbsenceVerificationScenario.PAGE_TYPE)
                .order(NEXT_ORDER)
                .upsert());
  }

  private void saveBody(
      String version, SourceOrder order, Optional<RunId> run, Set<String> blocks) {
    data.bodies.save(
        data.source.id(),
        new BodyCompletionStore.Body(
            data.scope.fingerprint(), OWNER, version, order, data.clock.instant(), blocks, run));
  }

  private void savePending(String version, RunId run, Set<String> blocks) {
    data.bodies.savePending(
        data.source.id(),
        new BodyCompletionStore.Pending(
            data.scope.fingerprint(), OWNER, version, BodyCommitScenario.ORDER, run, blocks));
  }
}

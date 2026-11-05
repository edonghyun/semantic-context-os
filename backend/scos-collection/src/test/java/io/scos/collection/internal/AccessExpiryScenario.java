package io.scos.collection.internal;

import io.scos.collection.internal.memory.MemoryRawAclStore;
import io.scos.collection.internal.projection.ProjectionSpaces;
import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.slack.SlackAssembly;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItems;
import io.scos.spi.source.GroupSnapshot;
import io.scos.spi.source.SourceAccessDirectory;
import io.scos.spi.source.SourceAccessDirectory.Snapshot;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryDocumentOwnership;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.memory.MemorySourceAccessDirectory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

/** 실제 메모리 저장소 위에서 비활성 원천과 관측 나이를 준비한다. */
final class AccessExpiryScenario {
  static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  private static final SourceInstanceId DRIVE = new SourceInstanceId("drive-a");
  private static final SourceInstanceId SLACK = new SourceInstanceId("slack-a");
  private final MutableClock clock = new MutableClock(T0.plusSeconds(86400));
  private final MemoryAclStore documents = new MemoryAclStore();
  private final MemoryDocumentOwnership ownership = new MemoryDocumentOwnership();
  private final CommitContext.Memory memory =
      CommitContext.Memory.create(new MemoryRawAclStore(), documents, ownership);
  private final MemorySourceAccessDirectory directory =
      new MemorySourceAccessDirectory(new MemoryIdentityStore());
  private final SourceAdapters adapters =
      SourceAdapters.of(
          List.of(
              GoogleDriveAssembly.adapter(
                  "http://127.0.0.1:1", GoogleDriveAssembly.CREDENTIAL_JSON, clock),
              SlackAssembly.adapter("http://127.0.0.1:1", "synthetic", clock)));
  private CancellationToken cancel = CancellationToken.none();
  private boolean enabled = true;
  private ProjectionSpaces configuredSpaces =
      new ProjectionSpaces(Map.of(), SpaceId.LEGACY, new ObjectMapper());
  private AccessExpiry expiry;
  private int directoryReads;
  private boolean failDriveDirectory;
  private Runnable duringDirectoryRead = () -> {};
  private Runnable duringRawRead = () -> {};
  private Runnable afterSourcesListed = () -> {};
  private Runnable afterRawPage = () -> {};
  private Runnable beforeNextTransaction = () -> {};
  private Runnable afterNextRawObservation = () -> {};
  private int rawObservations;
  private int ownershipClaims;
  private Runnable afterDocumentClaim = () -> {};
  private int rawReads;
  private int rawRowsRead;
  private int rawBodiesRead;
  private AccessCollection.Options options = AccessCollection.Options.DEFAULTS;
  private final SourceAccessDirectory observedDirectory =
      new SourceAccessDirectory() {
        @Override
        public Snapshot current(SourceInstanceId source) {
          directoryReads++;
          if (failDriveDirectory && source.equals(DRIVE))
            throw new IllegalStateException("expiry directory unavailable");
          duringDirectoryRead.run();
          return directory.current(source);
        }

        @Override
        public void save(SourceInstanceId source, Snapshot snapshot) {
          directory.save(source, snapshot);
        }
      };

  void givenExpiredDriveWithRawStatus(Status status) {
    givenDrive(T0.minusSeconds(1));
    memory.rawAcls().observe(DRIVE, "file:F1", RawAcl.observed(status, Set.of(), T0), T0);
  }

  void givenExpiredDrive() {
    givenDrive(T0);
  }

  void givenFreshDrive() {
    givenDrive(T0.plusSeconds(1));
  }

  private void givenDrive(Instant at) {
    givenDrive(at, T0);
  }

  private void givenDrive(Instant at, Instant decision) {
    givenRawDrive(at);
    var keys = Set.of(AccessKey.parse("user:drive-a:alice@example.com"));
    ownership.claim(SpaceId.LEGACY, DRIVE.value(), "file:F1");
    documents.put(SpaceId.LEGACY, List.of(new DocumentAcl("file:F1", Status.OK, keys, decision)));
  }

  private void givenRawDrive(Instant at) {
    memory
        .sources()
        .save(
            SourceInstances.disabled(
                SourceInstances.of(DRIVE, "google-drive", "{}", "env:FAKE", T0)));
    var keys = Set.of(AccessKey.parse("user:drive-a:alice@example.com"));
    memory.rawItems().apply(DRIVE, RawItems.item("file:F1").resourceType("gdrive.file").upsert());
    memory.rawAcls().observe(DRIVE, "file:F1", RawAcl.observed(Status.OK, keys, at), T0);
  }

  void givenExpiredDriveWithFutureDecision() {
    givenDrive(T0, T0.plusSeconds(86401));
  }

  void givenEmptyDriveSource() {
    memory
        .sources()
        .save(
            SourceInstances.disabled(
                SourceInstances.of(DRIVE, "google-drive", "{}", "env:FAKE", T0)));
  }

  void beginNewSweepAttempt() {
    cancel = CancellationToken.none();
    duringRawRead = () -> {};
    duringDirectoryRead = () -> {};
  }

  void cancelWhenRawTransactionBegins() {
    duringDirectoryRead = () -> beforeNextTransaction = cancel::cancel;
  }

  void givenSecondExpiredDriveDocument() {
    var keys = Set.of(AccessKey.parse("user:drive-a:alice@example.com"));
    memory.rawItems().apply(DRIVE, RawItems.item("file:F2").resourceType("gdrive.file").upsert());
    memory.rawAcls().observe(DRIVE, "file:F2", RawAcl.observed(Status.OK, keys, T0), T0);
    ownership.claim(SpaceId.LEGACY, DRIVE.value(), "file:F2");
    documents.put(SpaceId.LEGACY, List.of(new DocumentAcl("file:F2", Status.OK, keys, T0)));
  }

  void changeSourceAfterFirstRawClosure() {
    afterNextRawObservation = this::changeDriveConfiguration;
  }

  void givenExpiredRawWithVisibleDocument() {
    givenDrive(T0.minusSeconds(1));
    memory.rawAcls().observe(DRIVE, "file:F1", RawAcl.failed(AccessFailureReason.EXPIRED, T0), T0);
  }

  void givenDriveWithUnchangedDocument(String state) {
    switch (state) {
      case "unprojected" -> givenUnprojectedDrive();
      case "foreign-source" -> givenExpiredDriveWithForeignOwner();
      case "failed" -> {
        givenExpiredDrive();
        documents.put(
            SpaceId.LEGACY,
            List.of(
                new DocumentAcl(
                    "file:F1",
                    Status.FAILED,
                    Set.of(),
                    T0.plusSeconds(1),
                    AccessFailureReason.EXPIRED)));
      }
      case "foreign-space" -> {
        givenRawDrive(T0);
        ownership.claim(new SpaceId("foreign"), "other", "file:F1");
        documents.put(
            SpaceId.LEGACY,
            List.of(
                new DocumentAcl(
                    "file:F1", Status.OK, Set.of(AccessKey.parse("user:other:reader")), T0)));
      }
      default -> throw new IllegalArgumentException("Unknown document state");
    }
  }

  void changeConfiguredSpace() {
    configuredSpaces =
        new ProjectionSpaces(
            Map.of(DRIVE.value(), new SpaceId("changed")), SpaceId.LEGACY, new ObjectMapper());
  }

  void givenExpiredDriveWithForeignOwner() {
    givenRawDrive(T0);
    ownership.claim(SpaceId.LEGACY, "other", "file:F1");
    documents.put(
        SpaceId.LEGACY,
        List.of(
            new DocumentAcl(
                "file:F1",
                Status.OK,
                Set.of(AccessKey.parse("user:other:alice@example.com")),
                T0)));
  }

  void givenUnprojectedDrive() {
    givenRawDrive(T0);
  }

  Set<String> ownedDocuments() {
    return ownership.listDocumentIds(SpaceId.LEGACY, DRIVE.value());
  }

  int rawRowsRead() {
    return rawRowsRead;
  }

  int rawBodiesRead() {
    return rawBodiesRead;
  }

  void givenSlackCarrier() {
    givenExpiredSlackGroup();
    memory.rawItems().apply(SLACK, RawItems.item("C1:1700000000.000001").upsert());
  }

  void givenExpiredSlackGroup() {
    memory
        .sources()
        .save(SourceInstances.disabled(SourceInstances.of(SLACK, "slack", "{}", "env:FAKE", T0)));
    directory.save(
        SLACK, new Snapshot(Map.of(), Map.of("C1", new GroupSnapshot("C1", Set.of("U1"), T0))));
  }

  void sweep() {
    if (expiry == null)
      expiry =
          new AccessExpiry(
              memory
                  .ports()
                  .withReads(memory.runs(), observedRawItems(), observedRawAcls())
                  .withTransactions(new Transactions())
                  .stores()
                  .withSources(capturedSources()),
              adapters,
              configuredSpaces,
              documents,
              trackedOwnership(),
              observedDirectory,
              boundedClock(),
              options,
              enabled);
    expiry.sweep(cancel);
  }

  void closeDocumentWhileWaitingForClaim() {
    afterDocumentClaim =
        () ->
            documents.put(
                SpaceId.LEGACY,
                List.of(
                    new DocumentAcl(
                        "file:F1",
                        Status.FAILED,
                        Set.of(),
                        clock.instant(),
                        AccessFailureReason.MISSING_TARGET)));
  }

  void removeDocumentWhileWaitingForClaim() {
    afterDocumentClaim = () -> documents.delete(SpaceId.LEGACY, List.of("file:F1"));
  }

  void resetWorkCounts() {
    rawRowsRead = 0;
    rawObservations = 0;
    ownershipClaims = 0;
  }

  int rawObservations() {
    return rawObservations;
  }

  int ownershipClaims() {
    return ownershipClaims;
  }

  private DocumentOwnership trackedOwnership() {
    return new DocumentOwnership() {
      @Override
      public Claim claim(SpaceId space, String source, String document) {
        ownershipClaims++;
        var result = ownership.claim(space, source, document);
        var action = afterDocumentClaim;
        afterDocumentClaim = () -> {};
        action.run();
        return result;
      }

      @Override
      public Optional<SpaceId> ownedSpace(String source, String document) {
        return ownership.ownedSpace(source, document);
      }

      @Override
      public Set<String> listDocumentIds(SpaceId space, String source) {
        return ownership.listDocumentIds(space, source);
      }
    };
  }

  private SourceInstanceStore capturedSources() {
    return (SourceInstanceStore)
        Proxy.newProxyInstance(
            SourceInstanceStore.class.getClassLoader(),
            new Class<?>[] {SourceInstanceStore.class},
            (proxy, method, args) -> {
              try {
                var result = method.invoke(memory.sources(), args);
                if (method.getName().equals("listAll")) afterSourcesListed.run();
                return result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private RawAclStore observedRawAcls() {
    return (RawAclStore)
        Proxy.newProxyInstance(
            RawAclStore.class.getClassLoader(),
            new Class<?>[] {RawAclStore.class},
            (proxy, method, args) -> {
              try {
                var result = method.invoke(memory.rawAcls(), args);
                if (method.getName().equals("observe")) {
                  rawObservations++;
                  var action = afterNextRawObservation;
                  afterNextRawObservation = () -> {};
                  action.run();
                }
                return result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private final class Transactions implements TransactionOperations {
    @Override
    public <T> T execute(TransactionCallback<T> operation) {
      var action = beforeNextTransaction;
      beforeNextTransaction = () -> {};
      action.run();
      return memory.ports().tx().execute(operation);
    }
  }

  void changeSourceAfterList() {
    afterSourcesListed = this::changeDriveConfiguration;
  }

  void cancelWhenDirectoryTransactionBegins() {
    afterSourcesListed = () -> beforeNextTransaction = cancel::cancel;
  }

  void cancelDuringLockedDirectoryRead() {
    duringDirectoryRead = () -> duringDirectoryRead = cancel::cancel;
  }

  void refreshGroupAfterDirectoryProbe() {
    duringDirectoryRead =
        () ->
            beforeNextTransaction =
                () ->
                    directory.save(
                        SLACK,
                        new Snapshot(
                            Map.of(),
                            Map.of("C1", new GroupSnapshot("C1", Set.of("U2"), clock.instant()))));
  }

  void changeSourceAfterDirectoryRead() {
    duringDirectoryRead =
        () ->
            memory
                .sources()
                .save(SourceInstances.of(SLACK, "slack", "{\"changed\":true}", "env:FAKE", T0));
  }

  void observeFreshAfterCandidateRead() {
    afterRawPage =
        () -> {
          memory
              .rawAcls()
              .observe(
                  DRIVE,
                  "file:F1",
                  RawAcl.observed(
                      Status.OK,
                      Set.of(AccessKey.parse("user:drive-a:alice@example.com")),
                      clock.instant()),
                  clock.instant());
          afterRawPage = () -> {};
        };
  }

  void changeSourceAfterPage() {
    afterRawPage = this::changeDriveConfiguration;
  }

  private void changeDriveConfiguration() {
    memory
        .sources()
        .save(SourceInstances.of(DRIVE, "google-drive", "{\"changed\":true}", "env:FAKE", T0));
  }

  int rawReads() {
    return rawReads;
  }

  private RawItemStore observedRawItems() {
    return (RawItemStore)
        Proxy.newProxyInstance(
            RawItemStore.class.getClassLoader(),
            new Class<?>[] {RawItemStore.class},
            (proxy, method, args) -> {
              try {
                var result = method.invoke(memory.rawItems(), args);
                if (method.getName().equals("get")) {
                  rawReads++;
                  duringRawRead.run();
                }
                if (result instanceof RawItemStore.Page page) {
                  rawRowsRead += page.items().size();
                  rawBodiesRead +=
                      (int)
                          page.items().stream().filter(item -> item.content().isPresent()).count();
                }
                if (method.getName().equals("accessCandidates")) {
                  duringRawRead.run();
                  afterRawPage.run();
                }
                return result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  void cancelDuringRawRead() {
    duringRawRead = cancel::cancel;
  }

  void changeSourceDuringRawRead() {
    duringRawRead =
        () ->
            memory
                .sources()
                .save(
                    SourceInstances.of(
                        DRIVE, "google-drive", "{\"changed\":true}", "env:FAKE", T0));
  }

  boolean projectionRequested() {
    return memory.outbox().request(DRIVE).isPresent();
  }

  void givenOwnerlessDocument() {
    givenRawDrive(T0);
    documents.put(
        SpaceId.LEGACY,
        List.of(
            new DocumentAcl(
                "file:F1",
                Status.OK,
                Set.of(AccessKey.parse("user:drive-a:alice@example.com")),
                T0)));
  }

  private Clock boundedClock() {
    return new Clock() {
      private int reads;

      @Override
      public ZoneId getZone() {
        return clock.getZone();
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        if (++reads > 10_000) throw new AssertionError("만료 페이지 순회가 종료되지 않았다");
        return clock.instant();
      }
    };
  }

  DocumentAcl document() {
    return document("file:F1");
  }

  DocumentAcl document(String id) {
    return documents.get(SpaceId.LEGACY, List.of(id)).get(id);
  }

  RawAcl.Observed raw() {
    return memory.rawAcls().get(DRIVE, List.of("file:F1")).get("file:F1");
  }

  GroupSnapshot group() {
    return directory.current(SLACK).groups().get("C1");
  }

  void givenExpiredIdentity() {
    memory
        .sources()
        .save(SourceInstances.disabled(SourceInstances.of(SLACK, "slack", "{}", "env:FAKE", T0)));
    directory.save(
        SLACK,
        new Snapshot(
            Map.of(
                "U1",
                new SourceIdentityRecord(
                    "U1", Optional.of("alice@example.com"), true, false, false, T0)),
            Map.of()));
  }

  SourceIdentityRecord identity() {
    return directory.current(SLACK).identities().get("U1");
  }

  void cancelDuringDirectoryRead() {
    duringDirectoryRead = cancel::cancel;
  }

  int directoryReads() {
    return directoryReads;
  }

  void advance(Duration elapsed) {
    clock.advance(elapsed);
  }

  void maxAge(Duration maxAge) {
    options = new AccessCollection.Options(maxAge);
  }

  void failDriveDirectory() {
    failDriveDirectory = true;
  }

  void recoverDirectory() {
    failDriveDirectory = false;
  }

  void cancel() {
    cancel.cancel();
  }

  void disableCollection() {
    enabled = false;
  }
}

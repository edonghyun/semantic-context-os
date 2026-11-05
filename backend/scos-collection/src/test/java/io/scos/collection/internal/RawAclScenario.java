package io.scos.collection.internal;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.memory.MemoryRawAclStore;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.spi.access.AclStore;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DocumentAcl.Status;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.DocumentOwnership;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.UnitMaintenance;
import io.scos.store.memory.MemoryAclStore;
import io.scos.store.memory.MemoryDocumentOwnership;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.store.postgres.PostgresTestAdapters;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ACL 저장과 원본 조회가 같은 저장 상태를 읽는 계약 컨텍스트. */
public final class RawAclScenario {
  private final RawAclStore acls;
  private final RawItemStore items;
  private final UnitMaintenance units;
  private final AclStore documents;
  private final DocumentOwnership ownership;
  private final Runnable removeOwnership;
  private int sequence;
  private static final SpaceId SPACE = new SpaceId("legacy");

  private RawAclScenario(
      RawAclStore acls,
      RawItemStore items,
      UnitMaintenance units,
      AclStore documents,
      DocumentOwnership ownership,
      Runnable removeOwnership) {
    this.acls = acls;
    this.items = items;
    this.units = units;
    this.documents = documents;
    this.ownership = ownership;
    this.removeOwnership = removeOwnership;
  }

  public static RawAclScenario memory() {
    var documents = new MemoryAclStore();
    var ownership = new MemoryDocumentOwnership();
    var units =
        new MemoryUnitMaintenance(
            new MemoryUnitStore(documents, ownership), new MemoryVectorIndex());
    var acls = new MemoryRawAclStore(units, documents);
    return new RawAclScenario(
        acls,
        new MemoryRawItemStore(acls, documents, ownership),
        units,
        documents,
        ownership,
        () -> {});
  }

  public static RawAclScenario postgres(DataSource dataSource) {
    var stores = PostgresCollectionConfiguration.adapters(dataSource);
    return new RawAclScenario(
        stores.rawAcls(),
        stores.rawItems(),
        PostgresTestAdapters.unitMaintenance(dataSource),
        new PostgresIndexConfiguration().newAclStore(dataSource),
        new PostgresIndexConfiguration().newDocumentOwnership(dataSource),
        () -> JdbcClient.create(dataSource).sql("DELETE FROM document_spaces").update());
  }

  void givenItem(SourceInstanceId source, String id) {
    items.apply(
        source,
        RawItems.item(id)
            .resourceType("drive.file")
            .metadata(
                "{\"id\":\"file-1\",\"name\":\"Plan\",\"mimeType\":\"application/vnd.google-apps.document\"}")
            .upsert());
  }

  RawAcl.Observed current(SourceInstanceId source, String id) {
    return acls.get(source, List.of(id)).get(id);
  }

  RawItem item(SourceInstanceId source, String id) {
    return ((RawRecord.Active) items.get(source, id).orElseThrow()).item();
  }

  boolean observe(SourceInstanceId source, String id, RawAcl acl, Instant fallback) {
    return acls.observe(source, id, acl, fallback);
  }

  Map<String, RawAcl.Observed> observations(SourceInstanceId source, Collection<String> ids) {
    return acls.get(source, ids);
  }

  void forget(SourceInstanceId source, Collection<String> ids) {
    acls.delete(source, ids);
  }

  RawItemStore.Page accessCandidates(
      SourceInstanceId source,
      Set<String> kinds,
      Optional<Instant> cutoff,
      int limit,
      Optional<String> cursor) {
    return items.accessCandidates(source, kinds, cutoff, limit, cursor);
  }

  void completeExpiry(SourceInstanceId source, String id, Instant at, Optional<String> document) {
    items.completeAccessExpiry(source, id, at, SPACE, document);
  }

  RawItemStore.Page expiryCandidates(Instant cutoff) {
    return items.accessCandidates(
        RawAclStoreContract.SOURCE,
        Set.of("drive.file"),
        Optional.of(cutoff),
        10,
        Optional.empty());
  }

  void givenVisibleDocument(SpaceId space, String document) {
    documents.put(
        space,
        List.of(
            new DocumentAcl(
                document, Status.OK, Set.of(RawAclStoreContract.ALICE), RawAclStoreContract.T1)));
  }

  RawItemStore.Page activePage(SourceInstanceId source) {
    return items.listActive(source, 1, Optional.empty());
  }

  void upsert(SourceInstanceId source, RawMutation.Upsert mutation) {
    items.apply(source, mutation);
  }

  void givenOwnership(SpaceId space, SourceInstanceId source, String document) {
    ownership.claim(space, source.value(), document);
  }

  void givenProjectedDocument(SourceInstanceId source, String document) {
    units.upsert(
        SPACE,
        List.of(
            ContextUnits.unit("unit-" + ++sequence)
                .source(source.value())
                .inDocument(document)
                .build()));
  }

  void withoutOwnership() {
    removeOwnership.run();
  }

  void givenDocumentAcl(String document) {
    documents.put(
        SPACE, List.of(new DocumentAcl(document, Status.FAILED, Set.of(), RawAclStoreContract.T0)));
  }

  void deleteProjectedDocument(SourceInstanceId source, String document) {
    units.deleteByDocument(SPACE, source.value(), document, List.of());
  }

  int missing(SourceInstanceId source) {
    return acls.documentsMissingAcl(SPACE, source);
  }

  int missingInOtherSpace(SourceInstanceId source) {
    return acls.documentsMissingAcl(new SpaceId("other"), source);
  }
}

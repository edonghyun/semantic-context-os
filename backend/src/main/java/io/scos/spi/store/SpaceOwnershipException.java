package io.scos.spi.store;

/** 계약 위반(CONTRACTS A2): 문서는 첫 기록자의 공간이 소유하며 다른 공간의 쓰기는 거부된다. */
public final class SpaceOwnershipException extends RuntimeException {
  public final String documentId;
  public final SpaceId owner;

  public SpaceOwnershipException(String documentId, SpaceId owner, SpaceId attempted) {
    super("문서 " + documentId + "은(는) 공간 " + owner + " 소유 — 공간 " + attempted + "의 쓰기 거부");
    this.documentId = documentId;
    this.owner = owner;
  }
}

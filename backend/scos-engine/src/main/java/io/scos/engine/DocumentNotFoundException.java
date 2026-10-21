package io.scos.engine;

/** 공간에 그 문서가 없다(CONTRACTS D·E·H). */
public final class DocumentNotFoundException extends ContextEngineException {
  private final String documentId;

  public DocumentNotFoundException(String documentId) {
    super("document not found: " + documentId);
    this.documentId = documentId;
  }

  public String documentId() {
    return documentId;
  }
}

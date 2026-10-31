package io.scos.client;

import io.scos.spi.connector.RawItem;
import java.util.Optional;
import tools.jackson.core.JacksonException;

/** 원본 항목과 투영 문서 사이의 연결. JSON 표현과 잘못된 메타데이터 처리는 이 값이 소유한다. */
public record DocumentMetadata(String documentId) {
  public static DocumentMetadata of(String documentId) {
    return new DocumentMetadata(documentId);
  }

  /** 연결이 없거나 메타데이터가 깨졌으면 빈 값이다. 저장소의 외부 id 대체 정책은 호출자가 정한다. */
  public static Optional<DocumentMetadata> documentIdOf(RawItem item) {
    try {
      String id = ClientJson.MAPPER.readTree(item.metadataJson()).path("documentId").asString("");
      return id.isEmpty() ? Optional.empty() : Optional.of(of(id));
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }

  public String toJson() {
    return ClientJson.MAPPER.writeValueAsString(this);
  }
}

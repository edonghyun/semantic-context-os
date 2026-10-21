package io.scos.spi.connector;

import java.util.Optional;

/**
 * 원본 콘텐츠(TS RawContent, F8): 직렬화 바이트 ≤ 64KiB면 inline JSON, 아니면 blob(내용 주소 sha256). Blob의 data는 페이지
 * 커밋 전까지만 실려 있고(어댑터 → 저장소), 저장된 뒤에는 비어 있다 — blob 쓰기는 페이지 커밋과 같은 트랜잭션이다(ADR-0018 §5).
 */
public sealed interface RawContent {
  String mediaType();

  record Inline(String mediaType, String valueJson) implements RawContent {
    public Inline {
      if (mediaType == null || mediaType.isBlank()) throw new IllegalArgumentException("mediaType");
      if (valueJson == null) throw new IllegalArgumentException("valueJson must not be null");
    }
  }

  record Blob(String mediaType, String sha256, long size, Optional<byte[]> data)
      implements RawContent {
    public Blob {
      if (mediaType == null || mediaType.isBlank()) throw new IllegalArgumentException("mediaType");
      if (sha256 == null || sha256.length() != 64)
        throw new IllegalArgumentException("sha256 hex(64) required");
      if (size < 0) throw new IllegalArgumentException("size must be >= 0");
      data = data == null ? Optional.empty() : data.map(byte[]::clone);
    }

    /** 저장된 뒤의 참조형(데이터 없음). */
    public Blob stored() {
      return new Blob(mediaType, sha256, size, Optional.empty());
    }
  }
}

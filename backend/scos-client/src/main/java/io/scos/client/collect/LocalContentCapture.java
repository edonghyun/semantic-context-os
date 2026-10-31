package io.scos.client.collect;

import io.scos.client.ContentHash;
import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.RawContent;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** 직렬화 바이트가 64 KiB 이하면 Inline, 넘으면 Blob(sha256·크기·바이트). state는 둘 다 바이트로 저장한다. */
public final class LocalContentCapture implements ContentCapture {
  private final ContentHash hash = new ContentHash();

  static final int INLINE_LIMIT = 64 * 1024;

  @Override
  public RawContent json(String mediaType, String valueJson) {
    byte[] bytes = valueJson.getBytes(StandardCharsets.UTF_8);
    return bytes.length <= INLINE_LIMIT
        ? new RawContent.Inline(mediaType, valueJson)
        : blob(mediaType, bytes);
  }

  @Override
  public RawContent bytes(String mediaType, byte[] bytes) {
    return blob(mediaType, bytes);
  }

  private RawContent.Blob blob(String mediaType, byte[] bytes) {
    return new RawContent.Blob(mediaType, hash.sha256(bytes), bytes.length, Optional.of(bytes));
  }
}

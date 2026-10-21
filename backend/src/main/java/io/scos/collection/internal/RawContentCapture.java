package io.scos.collection.internal;

import io.scos.engine.support.text.Text;
import io.scos.spi.connector.ContentCapture;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawContentTooLargeException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * F8: JSON 값은 직렬화 바이트 ≤ inlineMaxBytes면 inline, 아니면 blob(sha256 내용 주소). 바이트 원본은 늘 blob. maxBytes
 * 초과는 {@link RawContentTooLargeException}.
 */
public final class RawContentCapture implements ContentCapture {
  public static final int DEFAULT_INLINE_MAX = 64 * 1024;
  public static final long DEFAULT_MAX = 50L * 1024 * 1024;

  private final int inlineMaxBytes;
  private final long maxBytes;

  public RawContentCapture(int inlineMaxBytes, long maxBytes) {
    this.inlineMaxBytes = inlineMaxBytes;
    this.maxBytes = maxBytes;
  }

  public static RawContentCapture defaults() {
    return new RawContentCapture(DEFAULT_INLINE_MAX, DEFAULT_MAX);
  }

  @Override
  public RawContent json(String mediaType, String valueJson) {
    byte[] payload = valueJson.getBytes(StandardCharsets.UTF_8);
    if (payload.length > maxBytes) throw new RawContentTooLargeException(payload.length, maxBytes);
    if (payload.length <= inlineMaxBytes) return new RawContent.Inline(mediaType, valueJson);
    return new RawContent.Blob(
        mediaType, Text.sha256Hex(valueJson), payload.length, Optional.of(payload));
  }

  /**
   * 바이트 원본(첨부 등, TS 스트림 입력 `{mediaType, stream}`): 크기와 무관하게 늘 blob이고 내용 주소는 바이트의 sha256이다(F4 리뷰
   * A-I3, ADR-0022 §4). maxBytes 초과는 {@link RawContentTooLargeException}. 바이트는 이미 메모리에 있다 — 받는 쪽이
   * `ProviderHttp.getBytes`의 상한으로 먼저 끊는다.
   */
  @Override
  public RawContent bytes(String mediaType, byte[] bytes) {
    if (bytes.length > maxBytes) throw new RawContentTooLargeException(bytes.length, maxBytes);
    return new RawContent.Blob(mediaType, Text.sha256Hex(bytes), bytes.length, Optional.of(bytes));
  }
}

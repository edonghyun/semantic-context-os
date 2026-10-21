package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawContentTooLargeException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 원본 캡처(F8, TS DefaultRawContentCapture). 바이트(첨부 원본 등, TS 스트림 입력)는 크기와 무관하게 늘 blob이고, 내용 주소는 바이트의
 * sha256이다. 상한을 넘으면 거부한다.
 */
@DisplayName("원본 캡처는 크기로 inline·blob을 고르고 상한을 넘으면 거부한다(F8)")
class RawContentCaptureTest {
  static final int INLINE_MAX = 8;
  static final long MAX = 16;
  static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G'};

  /** sha256("abc")(FIPS 180-2 예제). */
  static final String SHA256_ABC =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  final RawContentCapture capture = new RawContentCapture(INLINE_MAX, MAX);

  @Nested
  @DisplayName("바이트를 캡처하면")
  class Bytes {
    @Test
    @DisplayName("인라인 상한보다 작아도 blob이다")
    void alwaysBlob() {
      // when
      RawContent content = capture.bytes("image/png", PNG_HEAD);

      // then
      assertThat(content).isInstanceOf(RawContent.Blob.class);
    }

    @Test
    @DisplayName("내용 주소는 바이트의 sha256이다")
    void addressesBySha256OfBytes() {
      // when
      RawContent.Blob blob =
          (RawContent.Blob) capture.bytes("text/plain", "abc".getBytes(StandardCharsets.UTF_8));

      // then
      assertThat(blob.sha256()).isEqualTo(SHA256_ABC);
    }

    @Test
    @DisplayName("미디어 유형·크기·바이트를 그대로 담는다")
    void keepsMediaTypeSizeAndBytes() {
      // when
      RawContent.Blob blob = (RawContent.Blob) capture.bytes("image/png", PNG_HEAD);

      // then
      assertThat(blob)
          .extracting(
              RawContent.Blob::mediaType,
              RawContent.Blob::size,
              b -> HexFormat.of().formatHex(b.data().orElseThrow()))
          .containsExactly("image/png", (long) PNG_HEAD.length, HexFormat.of().formatHex(PNG_HEAD));
    }

    @Test
    @DisplayName("상한과 같은 크기는 받는다")
    void acceptsAtLimit() {
      // when
      RawContent content = capture.bytes("application/octet-stream", new byte[(int) MAX]);

      // then
      assertThat(content).isInstanceOf(RawContent.Blob.class);
    }

    @Test
    @DisplayName("상한을 넘으면 거부한다")
    void rejectsOverLimit() {
      // when·then
      assertThatThrownBy(() -> capture.bytes("application/octet-stream", new byte[(int) MAX + 1]))
          .isInstanceOf(RawContentTooLargeException.class);
    }
  }

  @Nested
  @DisplayName("JSON 값을 캡처하면")
  class Json {
    /** 직렬화 바이트 수가 n인 JSON 문자열 값. */
    static String jsonOfBytes(int n) {
      return "\"" + "a".repeat(n - 2) + "\"";
    }

    @Test
    @DisplayName("인라인 상한과 같은 크기는 inline이다")
    void inlineAtInlineLimit() {
      // when
      RawContent content = capture.json("application/json", jsonOfBytes(INLINE_MAX));

      // then
      assertThat(content).isInstanceOf(RawContent.Inline.class);
    }

    @Test
    @DisplayName("인라인 상한을 넘으면 blob이다")
    void blobOverInlineLimit() {
      // when
      RawContent content = capture.json("application/json", jsonOfBytes(INLINE_MAX + 1));

      // then
      assertThat(content).isInstanceOf(RawContent.Blob.class);
    }

    @Test
    @DisplayName("상한과 같은 크기는 받는다")
    void acceptsAtLimit() {
      // when
      RawContent content = capture.json("application/json", jsonOfBytes((int) MAX));

      // then
      assertThat(content).isInstanceOf(RawContent.Blob.class);
    }

    @Test
    @DisplayName("상한을 넘으면 거부한다")
    void rejectsOverLimit() {
      // when·then
      assertThatThrownBy(() -> capture.json("application/json", jsonOfBytes((int) MAX + 1)))
          .isInstanceOf(RawContentTooLargeException.class);
    }

    @Test
    @DisplayName("거부 메시지는 TS RawContentTooLarge 문구다(상한만, 크기·내용 없음)")
    void rejectsWithTsMessage() {
      // when·then
      assertThatThrownBy(() -> capture.json("application/json", jsonOfBytes((int) MAX + 1)))
          .hasMessage("Raw content exceeds " + MAX + " byte limit");
    }
  }

  @Nested
  @DisplayName("한국어 JSON 값을 캡처하면 크기는 문자 수가 아니라 UTF-8 바이트 수다")
  class KoreanJson {
    /** 5문자, 11바이트("가"는 UTF-8 3바이트): 문자 수로는 인라인 상한 안, 바이트 수로는 밖. */
    static final String FIVE_CHARS_ELEVEN_BYTES = "\"가가가\"";

    /** 7문자, 17바이트: 문자 수로는 상한 안, 바이트 수로는 밖. */
    static final String SEVEN_CHARS_SEVENTEEN_BYTES = "\"가가가가가\"";

    @Test
    @DisplayName("문자 수가 인라인 상한 안이어도 바이트 수가 넘으면 blob이다")
    void blobByUtf8Bytes() {
      // when
      RawContent content = capture.json("application/json", FIVE_CHARS_ELEVEN_BYTES);

      // then
      assertThat(content).isInstanceOf(RawContent.Blob.class);
    }

    @Test
    @DisplayName("blob 크기는 UTF-8 바이트 수다")
    void blobSizeIsUtf8Bytes() {
      // when
      RawContent.Blob blob =
          (RawContent.Blob) capture.json("application/json", FIVE_CHARS_ELEVEN_BYTES);

      // then
      assertThat(blob.size()).isEqualTo(11L);
    }

    @Test
    @DisplayName("문자 수가 상한 안이어도 바이트 수가 넘으면 거부한다")
    void rejectsByUtf8Bytes() {
      // when·then
      assertThatThrownBy(() -> capture.json("application/json", SEVEN_CHARS_SEVENTEEN_BYTES))
          .isInstanceOf(RawContentTooLargeException.class);
    }
  }
}

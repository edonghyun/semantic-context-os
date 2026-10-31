package io.scos.spi.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 기본 64 KiB 인라인 정책을 사용하는 원본 캡처의 공통 계약. 실제 구현과 대역이 같은 사례를 따른다. */
@DisplayName("콘텐츠 캡처는 원문 바이트와 미디어 타입을 보존한다(E2)")
public abstract class ContentCaptureContract {
  private ContentCapture capture;

  protected abstract ContentCapture newCapture();

  @BeforeEach
  void setUpCapture() {
    capture = newCapture();
  }

  @Test
  @DisplayName("작은 JSON은 미디어 타입과 원문을 인라인으로 보존한다")
  void smallJsonIsInline() {
    // when
    RawContent content = capture.json("application/example+json", "{ \"text\": \"가\" }");

    // then
    assertThat(content)
        .isEqualTo(new RawContent.Inline("application/example+json", "{ \"text\": \"가\" }"));
  }

  @Test
  @DisplayName("빈 문자열도 인라인으로 보존한다")
  void emptyJsonIsInline() {
    // when
    RawContent content = capture.json("application/json", "");

    // then
    assertThat(content).isEqualTo(new RawContent.Inline("application/json", ""));
  }

  @Test
  @DisplayName("65536바이트 JSON은 인라인이다")
  void inlineAtBoundary() {
    // given
    String json = "\"" + "a".repeat(65534) + "\"";

    // when
    RawContent content = capture.json("application/json", json);

    // then
    assertThat(content).isInstanceOf(RawContent.Inline.class);
  }

  @Test
  @DisplayName("65537바이트 JSON은 Blob이다")
  void blobAboveBoundary() {
    // given
    String json = "\"" + "a".repeat(65535) + "\"";

    // when
    RawContent content = capture.json("application/json", json);

    // then
    assertThat(content).isInstanceOf(RawContent.Blob.class);
  }

  @Test
  @DisplayName("바이트 원본은 비어 있어도 Blob이다")
  void emptyBytesAreBlob() {
    // when
    RawContent content = capture.bytes("application/octet-stream", new byte[0]);

    // then
    assertThat(content).isInstanceOf(RawContent.Blob.class);
  }

  @Test
  @DisplayName("바이트 Blob의 내용 주소는 SHA-256이다")
  void bytesBlobHash() {
    // when
    RawContent.Blob blob =
        (RawContent.Blob) capture.bytes("text/plain", "abc".getBytes(StandardCharsets.UTF_8));

    // then
    assertThat(blob.sha256())
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Nested
  @DisplayName("인라인 한도를 넘는 UTF-8 JSON이 주어진 상태에서")
  class LargeUtf8Json {
    private String json;

    @BeforeEach
    void setUpJson() {
      json = "\"" + "가".repeat(21845) + "\"";
    }

    @Test
    @DisplayName("문자 수가 적어도 UTF-8 바이트 수가 상한을 넘으면 Blob이다")
    void blobByUtf8Size() {
      // when
      RawContent content = capture.json("application/example+json", json);

      // then
      assertThat(content).isInstanceOf(RawContent.Blob.class);
    }

    @Test
    @DisplayName("JSON Blob의 미디어 타입을 보존한다")
    void jsonBlobMediaType() {
      // when
      RawContent.Blob blob = (RawContent.Blob) capture.json("application/example+json", json);

      // then
      assertThat(blob.mediaType()).isEqualTo("application/example+json");
    }

    @Test
    @DisplayName("JSON Blob의 크기는 UTF-8 바이트 수다")
    void jsonBlobSize() {
      // when
      RawContent.Blob blob = (RawContent.Blob) capture.json("application/example+json", json);

      // then
      assertThat(blob.size()).isEqualTo(65537L);
    }

    @Test
    @DisplayName("JSON Blob은 원문 UTF-8 바이트를 담는다")
    void jsonBlobData() {
      // when
      RawContent.Blob blob = (RawContent.Blob) capture.json("application/example+json", json);

      // then
      assertThat(new String(blob.data().orElseThrow(), StandardCharsets.UTF_8))
          .isEqualTo("\"" + "가".repeat(21845) + "\"");
    }

    @Test
    @DisplayName("JSON Blob의 내용 주소는 UTF-8 원문의 SHA-256이다")
    void jsonBlobHash() {
      // when
      RawContent.Blob blob = (RawContent.Blob) capture.json("application/example+json", json);

      // then
      assertThat(blob.sha256())
          .isEqualTo("cacbbd3e5eac8d864db7464bed96a23e509ef7aa798701b33aa0f311bf20d917");
    }
  }

  @Nested
  @DisplayName("서로 다른 바이트 세 개가 주어진 상태에서")
  class BinaryContent {
    private byte[] original;

    @BeforeEach
    void setUpBytes() {
      original = new byte[] {0, -1, 65};
    }

    @Test
    @DisplayName("바이트 Blob의 미디어 타입을 보존한다")
    void bytesBlobMediaType() {
      // when
      RawContent.Blob blob = (RawContent.Blob) capture.bytes("image/png", original);

      // then
      assertThat(blob.mediaType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("바이트 Blob의 크기를 보존한다")
    void bytesBlobSize() {
      // when
      RawContent.Blob blob = (RawContent.Blob) capture.bytes("image/png", original);

      // then
      assertThat(blob.size()).isEqualTo(3L);
    }

    @Test
    @DisplayName("바이트 Blob은 호출자가 원본을 바꿔도 캡처한 내용을 보존한다")
    void bytesBlobDataIsSnapshot() {
      // when
      RawContent.Blob blob = (RawContent.Blob) capture.bytes("image/png", original);

      // when: 캡처 뒤 호출자가 원본을 바꾸면
      original[0] = 9;

      // then
      assertThat(blob.data().orElseThrow()).containsExactly(0, -1, 65);
    }
  }
}

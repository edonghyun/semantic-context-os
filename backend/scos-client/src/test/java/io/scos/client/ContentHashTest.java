package io.scos.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("클라이언트 콘텐츠 해시는 바이트의 SHA-256 소문자 16진수다(B6)")
class ContentHashTest {
  private final ContentHash hash = new ContentHash();

  @Test
  @DisplayName("빈 바이트도 SHA-256 지문을 갖는다")
  void hashesEmptyBytes() {
    // given
    byte[] bytes = new byte[0];

    // when
    String actual = hash.sha256(bytes);

    // then
    assertThat(actual)
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  @DisplayName("UTF-8 바이트를 64자리 소문자 지문으로 바꾼다")
  void hashesUtf8Bytes() {
    // given
    byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);

    // when
    String actual = hash.sha256(bytes);

    // then
    assertThat(actual)
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}

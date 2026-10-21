package io.scos.collection.internal.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.spi.connector.CallFailureException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** `file:<경로>`(TS FileCredentialResolver): 앞뒤 공백을 떼고, 실패 오류에 내용을 싣지 않는다. */
@Tag("process")
@DisplayName("file: 자격 증명은 파일 내용이고, 실패에 내용을 싣지 않는다(TS)")
class FileCredentialsTest {
  static final String SECRET = "xoxb-file-secret";
  final FileCredentials files = new FileCredentials();

  /** 크기 상한 1 MiB를 손으로 쓴 값(제품 상수를 기대값으로 쓰지 않는다). */
  static final int ONE_MIB = 1_048_576;

  @TempDir Path dir;

  Path write(String name, String contents) throws IOException {
    return Files.writeString(dir.resolve(name), contents);
  }

  /** FIFO 하나를 만든다. 만들지 못하면 given 준비 실패로 멈춘다. */
  Path givenFifo(String name) throws IOException, InterruptedException {
    Path fifo = dir.resolve(name);
    Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
    if (mkfifo.waitFor() != 0) {
      throw new IllegalStateException("given 준비 실패: FIFO를 만들지 못했다 " + fifo);
    }
    return fifo;
  }

  @Test
  @DisplayName("파일 내용의 앞뒤 공백을 떼어 돌려준다")
  void returnsTrimmedContents() throws IOException {
    // given
    Path token = write("token", SECRET + "\n");

    // when
    String value = files.resolve(token.toString());

    // then
    assertThat(value).isEqualTo(SECRET);
  }

  @Test
  @DisplayName("공백뿐인 파일은 경로를 담아 비었다고 거부한다")
  void rejectsBlankFile() throws IOException {
    // given
    Path empty = write("empty", "  \n");

    // when·then
    assertThatThrownBy(() -> files.resolve(empty.toString()))
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining("empty")
        .hasMessageContaining(empty.toString());
  }

  @Test
  @DisplayName("없는 파일은 경로를 담아 읽을 수 없다고 거부한다")
  void rejectsMissingFile() {
    // given
    Path missing = dir.resolve("missing");

    // when·then
    assertThatThrownBy(() -> files.resolve(missing.toString()))
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining("could not be read")
        .hasMessageContaining(missing.toString());
  }

  @Test
  @DisplayName("읽기 실패 오류는 파일 내용도 원인 예외도 싣지 않는다")
  void readFailureCarriesNoContents() throws IOException {
    // given
    Path unreadable = write("unreadable", SECRET);
    Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));

    // when·then
    assertThatThrownBy(() -> files.resolve(unreadable.toString()))
        .isInstanceOf(CallFailureException.class)
        .hasMessageNotContaining(SECRET)
        .hasNoCause();
  }

  @Test
  @DisplayName("BOM과 NBSP도 TS trim처럼 뗀다")
  void trimsBomAndNbspLikeTs() throws IOException {
    // given
    Path token = write("bom-token", "\uFEFF" + SECRET + "\u00A0\n");

    // when
    String value = files.resolve(token.toString());

    // then
    assertThat(value).isEqualTo(SECRET);
  }

  @Test
  @DisplayName("잘못된 UTF-8은 TS처럼 대체 문자로 읽는다")
  void replacesMalformedUtf8LikeTs() throws IOException {
    // given
    Path token = Files.write(dir.resolve("malformed"), new byte[] {'a', (byte) 0xFF, 'b'});

    // when
    String value = files.resolve(token.toString());

    // then
    assertThat(value).isEqualTo("a\uFFFDb");
  }

  @Test
  @DisplayName("정규 파일이 아니면(FIFO) 막히지 않고 거부한다")
  void rejectsFifoWithoutBlocking() throws Exception {
    // given
    Path fifo = givenFifo("fifo");

    // when·then
    assertThatThrownBy(() -> files.resolve(fifo.toString()))
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining("not a regular file");
  }

  @Test
  @DisplayName("크기 상한을 넘는 파일은 읽지 않고 거부한다")
  void rejectsOversizedFile() throws IOException {
    // given
    Path huge = Files.write(dir.resolve("huge"), new byte[ONE_MIB + 1]);

    // when·then
    assertThatThrownBy(() -> files.resolve(huge.toString()))
        .isInstanceOf(CallFailureException.class)
        .hasMessageContaining("exceeds");
  }

  @Test
  @DisplayName("크기 상한과 같은 파일은 읽는다")
  void readsFileAtLimit() throws IOException {
    // given
    byte[] atLimit = new byte[ONE_MIB];
    Arrays.fill(atLimit, (byte) 'a');
    Path token = Files.write(dir.resolve("at-limit"), atLimit);

    // when
    String resolved = files.resolve(token.toString());

    // then
    assertThat(resolved).hasSize(1_048_576);
  }
}

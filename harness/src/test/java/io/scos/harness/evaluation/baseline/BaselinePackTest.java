package io.scos.harness.evaluation.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("기준선 판정셋은 baseline.terms를 검증한다")
class BaselinePackTest {
  @TempDir Path dir;

  private Path pack(String queryFields) throws IOException {
    Path file = dir.resolve("pack.json");
    Files.writeString(
        file,
        "{\"name\":\"n\",\"version\":1,\"queries\":[{\"query\":\"q\",\"expectedDocumentIds\":[\"a\"],"
            + queryFields
            + "}]}");
    return file;
  }

  @Test
  @DisplayName("gitTerms가 없으면 terms를 쓴다")
  void gitTermsDefaultToTerms() throws IOException {
    // given
    Path file = pack("\"kind\":\"why\",\"baseline\":{\"terms\":[\"x\",\"y\"]}");

    // when
    BaselinePack.Query query = BaselinePack.read(file).get(0);

    // then
    assertThat(query.terms()).containsExactly("x", "y");
    assertThat(query.gitTerms()).containsExactly("x", "y");
    assertThat(query.kind()).isEqualTo("why");
  }

  @Test
  @DisplayName("terms가 없거나 비었거나 3개를 넘으면 거부한다")
  void rejectsBadTerms() throws IOException {
    // given
    Path missing = pack("\"kind\":\"why\"");
    Path empty = pack("\"baseline\":{\"terms\":[]}");
    Path four = pack("\"baseline\":{\"terms\":[\"a\",\"b\",\"c\",\"d\"]}");

    // when·then
    for (Path file : List.of(missing, empty, four))
      assertThatThrownBy(() -> BaselinePack.read(file))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("query 0: baseline.terms");
  }

  @Test
  @DisplayName("kind가 why·resume이 아니면 거부한다")
  void rejectsUnknownKind() throws IOException {
    // given
    Path file = pack("\"kind\":\"other\",\"baseline\":{\"terms\":[\"x\"]}");

    // when·then
    assertThatThrownBy(() -> BaselinePack.read(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kind");
  }
}

package io.scos.client.source.memory;

import static io.scos.client.source.CollectedPages.deletes;
import static io.scos.client.source.CollectedPages.item;
import static io.scos.client.source.CollectedPages.last;
import static io.scos.client.source.CollectedPages.upserts;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.client.ClientJson;
import io.scos.client.source.SourceAdapterScenario;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("자동 메모리 원천 어댑터는 변경과 삭제를 안전하게 구분한다(CG-6)")
class MemoryAdapterTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);

  @TempDir Path root;
  private Path memory;
  private MemoryAdapter adapter;
  private SourceAdapterScenario scenario;

  @BeforeEach
  void setUp() throws IOException {
    memory = Files.createDirectories(root.resolve("projects/d/memory"));
    adapter = new MemoryAdapter(root.resolve("projects"), CLOCK);
    scenario = new SourceAdapterScenario(adapter, CLOCK);
  }

  private void write(String name, String content) throws IOException {
    Files.writeString(memory.resolve(name), content, StandardCharsets.UTF_8);
  }

  private List<CollectionPage> collect(Optional<Checkpoint> checkpoint) {
    return scenario.collect(checkpoint);
  }

  @Test
  @DisplayName("나열한 뒤 읽기 전에 사라진 파일은 그 주기에 삭제로 내지 않는다")
  void doesNotDeleteFileVanishedAfterListing() throws IOException {
    // given
    Optional<Checkpoint> before = givenTwoCollectedFiles();
    write("a.md", "첫 메모 수정");
    scenario.afterCapture(this::deleteSecondFile);
    // when
    List<CollectionPage> pages = collect(before);
    // then
    assertThat(
            pages.stream()
                .flatMap(page -> page.mutations().stream())
                .map(mutation -> mutation.getClass().getSimpleName() + ":" + mutation.externalId()))
        .containsExactly("Upsert:d/a.md");
    assertThat(
            ClientJson.MAPPER
                .readTree(last(pages).orElseThrow().valueJson())
                .path("files")
                .propertyNames())
        .containsExactly("d/a.md", "d/b.md");
    assertThat(memory.resolve("b.md")).doesNotExist();
  }

  private Optional<Checkpoint> givenTwoCollectedFiles() throws IOException {
    write("a.md", "첫 메모");
    write("b.md", "둘째 메모");
    List<CollectionPage> pages = collect(Optional.empty());
    if (!upserts(pages).equals(List.of("d/a.md", "d/b.md")))
      throw new IllegalStateException("given 준비 실패: 두 파일 수집");
    return last(pages);
  }

  private void deleteSecondFile() {
    try {
      Files.delete(memory.resolve("b.md"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Nested
  @DisplayName("두 메모리 파일을 수집한 상태에서")
  class CollectedFiles {
    private Optional<Checkpoint> checkpoint;

    @BeforeEach
    void givenCollectedFiles() throws IOException {
      checkpoint = givenTwoCollectedFiles();
    }

    @Test
    @DisplayName("바뀐 파일만 다시 낸다")
    void upsertsChangedOnly() throws IOException {
      // given
      write("b.md", "둘째 메모 수정");
      // when
      List<CollectionPage> pages = collect(checkpoint);
      // then
      assertThat(upserts(pages)).containsExactly("d/b.md");
    }

    @Test
    @DisplayName("변경분을 수집한 뒤 다시 수집하면 페이지를 내지 않는다")
    void skipsCollectedChange() throws IOException {
      // given
      write("b.md", "둘째 메모 수정");
      Optional<Checkpoint> changed = givenChangedFileCollected();
      // when
      List<CollectionPage> pages = collect(changed);
      // then
      assertThat(pages).isEmpty();
    }

    private Optional<Checkpoint> givenChangedFileCollected() {
      List<CollectionPage> pages = collect(checkpoint);
      if (!upserts(pages).equals(List.of("d/b.md")))
        throw new IllegalStateException("given 준비 실패: 바뀐 b.md 수집");
      return last(pages);
    }

    @Test
    @DisplayName("지운 파일만 삭제로 낸다")
    void deletesRemovedFile() throws IOException {
      // given
      Files.delete(memory.resolve("a.md"));
      // when
      List<CollectionPage> pages = collect(checkpoint);
      // then
      assertThat(deletes(pages)).containsExactly("d/a.md");
      assertThat(upserts(pages)).isEmpty();
    }

    @Test
    @DisplayName("삭제를 수집한 뒤 다시 수집하면 페이지를 내지 않는다")
    void skipsCollectedDeletion() throws IOException {
      // given
      Files.delete(memory.resolve("a.md"));
      Optional<Checkpoint> deleted = givenDeletionCollected();
      // when
      List<CollectionPage> pages = collect(deleted);
      // then
      assertThat(pages).isEmpty();
    }

    private Optional<Checkpoint> givenDeletionCollected() {
      List<CollectionPage> pages = collect(checkpoint);
      if (!deletes(pages).equals(List.of("d/a.md")) || !upserts(pages).isEmpty())
        throw new IllegalStateException("given 준비 실패: a.md 삭제 수집");
      return last(pages);
    }
  }

  @Nested
  @DisplayName("자격 증명이 든 메모리 파일이 있으면")
  class SecretMemory {
    @BeforeEach
    void givenSecretMemory() throws IOException {
      write("MEMORY.md", "- 키 sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789 보관\n");
    }

    @Test
    @DisplayName("원본은 메모리 파일 자원 타입을 갖는다")
    void carriesMemoryResourceType() {
      // when
      RawItem item = item(collect(Optional.empty()), "d/MEMORY.md");
      // then
      assertThat(item.resourceType())
          .isEqualTo(MemoryAdapter.RESOURCE_TYPE)
          .isEqualTo("claude.memory.file");
    }

    @Test
    @DisplayName("원본에는 메모리 문서 식별자가 붙는다")
    void carriesDocumentId() {
      // when
      RawItem item = item(collect(Optional.empty()), "d/MEMORY.md");
      // then
      assertThat(item.metadataJson()).isEqualTo("{\"documentId\":\"memory/d/MEMORY.md\"}");
    }

    @Test
    @DisplayName("원본 본문에는 자격 증명이 가려진다")
    void redactsStoredText() {
      // when
      RawItem item = item(collect(Optional.empty()), "d/MEMORY.md");
      // then
      String stored = ((RawContent.Inline) item.content().orElseThrow()).valueJson();
      assertThat(ClientJson.MAPPER.readTree(stored).path("text").asString())
          .isEqualTo("- 키 [REDACTED] 보관\n");
    }
  }

  @Test
  @DisplayName("큰 메모리는 가린 내용을 Blob으로 캡처한다")
  void capturesLargeMemoryAsBlob() throws IOException {
    // given
    write("a.md", "가".repeat(23000) + " sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAA");

    // when
    RawItem item = item(collect(Optional.empty()), "d/a.md");

    // then
    assertThat(item.content().orElseThrow())
        .isInstanceOfSatisfying(
            RawContent.Blob.class,
            blob ->
                assertThat(
                        ClientJson.MAPPER
                            .readTree(new String(blob.data().orElseThrow(), StandardCharsets.UTF_8))
                            .path("text")
                            .asString())
                    .isEqualTo("가".repeat(23000) + " [REDACTED]"));
  }

  @Test
  @DisplayName("원본의 갱신 시각은 수집 시각이 아니라 파일 수정 시각이다")
  void sourceUpdatedAtIsFileMtime() throws IOException {
    // given
    write("a.md", "메모");
    Files.setLastModifiedTime(
        memory.resolve("a.md"), FileTime.from(Instant.parse("2026-09-01T03:04:05Z")));

    // when
    RawItem item = item(collect(Optional.empty()), "d/a.md");

    // then
    assertThat(item.sourceUpdatedAt()).contains("2026-09-01T03:04:05Z");
    assertThat(item.observedAt()).isEqualTo(Instant.parse("2026-10-28T00:00:00Z"));
  }

  @Test
  @DisplayName("지운 파일을 삭제로 내려면 스냅숏 정리가 아니라 증분 범위여야 한다")
  void coverageIsIncremental() {
    // when
    CollectionCoverage coverage =
        adapter.coverage(scenario.config(), CollectionMode.SNAPSHOT, Optional.empty());

    // then
    assertThat(coverage).isEqualTo(CollectionCoverage.INCREMENTAL);
  }

  @Test
  @DisplayName("메모리 폴더 밖 파일과 .md가 아닌 파일은 보지 않는다")
  void ignoresOtherFiles() throws IOException {
    // given
    write("a.md", "메모");
    write("notes.txt", "무시");
    Files.writeString(root.resolve("projects/d/s1.md"), "세션 옆 파일");

    // when
    List<CollectionPage> pages = collect(Optional.empty());

    // then
    assertThat(upserts(pages)).containsExactly("d/a.md");
  }

  @Nested
  @DisplayName("수집한 두 파일이 바뀌고 첫 파일을 읽을 수 없으면")
  class UnreadableFile {
    private Optional<Checkpoint> checkpoint;

    @BeforeEach
    void givenUnreadableChangedFile() throws IOException {
      checkpoint = givenTwoCollectedFiles();
      write("a.md", "# a\n바뀜");
      write("b.md", "# b\n바뀜");
      Files.setPosixFilePermissions(
          memory.resolve("a.md"), PosixFilePermissions.fromString("---------"));
    }

    @AfterEach
    void restorePermissions() throws IOException {
      Files.setPosixFilePermissions(
          memory.resolve("a.md"), PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    @DisplayName("형제 파일의 변경만 내고 읽지 못한 파일을 삭제하지 않는다")
    void collectsSiblingWithoutDeletingUnreadableFile() {
      // when
      List<CollectionPage> pages = collect(checkpoint);
      // then
      assertThat(
              pages.stream()
                  .flatMap(p -> p.mutations().stream())
                  .map(m -> m.getClass().getSimpleName() + ":" + m.externalId()))
          .containsExactly("Upsert:d/b.md");
    }

    @Test
    @DisplayName("읽지 못한 파일 하나를 거절로 보고한다")
    void reportsRejection() {
      // when
      List<CollectionPage> pages = collect(checkpoint);
      // then
      assertThat(pages.stream().mapToInt(p -> p.rejections().size()).sum()).isEqualTo(1);
    }

    @Test
    @DisplayName("권한을 복구하면 읽지 못했던 변경분만 다시 낸다")
    void recoversUnreadableChange() throws IOException {
      // given
      Optional<Checkpoint> blocked = givenRejectedCollection();
      restorePermissions();
      // when
      List<CollectionPage> pages = collect(blocked);
      // then
      assertThat(upserts(pages)).containsExactly("d/a.md");
    }

    private Optional<Checkpoint> givenRejectedCollection() {
      List<CollectionPage> pages = collect(checkpoint);
      if (!upserts(pages).equals(List.of("d/b.md"))
          || pages.stream().mapToInt(p -> p.rejections().size()).sum() != 1)
        throw new IllegalStateException("given 준비 실패: 첫 파일 거절과 형제 파일 수집");
      return last(pages);
    }
  }

  @Test
  @DisplayName("기존 sqlite 체크포인트의 해시와 같으면 다시 내지 않는다")
  void resumesLegacySqliteCheckpoint() throws IOException {
    // given
    write("a.md", "");
    Optional<Checkpoint> saved =
        scenario.givenStoredCheckpoint(
            root.resolve("state.sqlite"),
            new Checkpoint(
                1,
                "{\"files\":{\"d/a.md\":{\"size\":0,\"mtime\":\"2026-09-01T00:00:00Z\",\"sha\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}}"));

    // when
    List<CollectionPage> pages = collect(saved);

    // then
    assertThat(pages).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"files\":{\"d/a.md\":{\"mtime\":\"2026-09-01T00:00:00Z\",\"sha\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}}",
        "{\"files\":{\"d/a.md\":{\"size\":null,\"mtime\":\"2026-09-01T00:00:00Z\",\"sha\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}}"
      })
  @DisplayName("저장된 크기가 누락되거나 null이어도 해시가 같은 파일은 다시 내지 않는다")
  void resumesLegacyDefaultSize(String valueJson) throws IOException {
    // given
    write("a.md", "");
    Optional<Checkpoint> saved =
        scenario.givenStoredCheckpoint(root.resolve("state.sqlite"), new Checkpoint(1, valueJson));

    // when
    List<CollectionPage> pages = collect(saved);

    // then
    assertThat(pages).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"files\":{\"d/a.md\":{\"mtime\":\"2026-09-01T00:00:00Z\",\"sha\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}}",
        "{\"files\":{\"d/a.md\":{\"size\":null,\"mtime\":\"2026-09-01T00:00:00Z\",\"sha\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}}"
      })
  @DisplayName("저장된 크기가 누락되거나 null이면 다음 체크포인트에 0으로 보존한다")
  void preservesLegacyDefaultSize(String valueJson) throws IOException {
    // given
    write("a.md", "");
    Files.setLastModifiedTime(
        memory.resolve("a.md"), FileTime.from(Instant.parse("2026-09-02T00:00:00Z")));
    write("b.md", "");
    Files.setLastModifiedTime(
        memory.resolve("b.md"), FileTime.from(Instant.parse("2026-09-01T00:00:00Z")));
    Optional<Checkpoint> saved =
        scenario.givenStoredCheckpoint(root.resolve("state.sqlite"), new Checkpoint(1, valueJson));

    // when
    List<CollectionPage> pages = collect(saved);

    // then
    assertThat(pages)
        .extracting(p -> p.nextCheckpoint().valueJson())
        .containsExactly(
            "{\"files\":{\"d/a.md\":{\"size\":0,\"mtime\":\"2026-09-01T00:00:00Z\",\"sha\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"},\"d/b.md\":{\"size\":0,\"mtime\":\"2026-09-01T00:00:00Z\",\"sha\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}}");
  }

  @Test
  @DisplayName("체크포인트를 기존 파일 상태 JSON 형식으로 저장한다")
  void writesLegacyCheckpointShape() throws IOException {
    // given
    write("a.md", "");
    Files.setLastModifiedTime(
        memory.resolve("a.md"), FileTime.from(Instant.parse("2026-09-01T00:00:00Z")));

    // when
    List<CollectionPage> pages = collect(Optional.empty());

    // then
    assertThat(pages)
        .extracting(p -> p.nextCheckpoint().valueJson())
        .containsExactly(
            "{\"files\":{\"d/a.md\":{\"size\":0,\"mtime\":\"2026-09-01T00:00:00Z\",\"sha\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}}");
  }
}

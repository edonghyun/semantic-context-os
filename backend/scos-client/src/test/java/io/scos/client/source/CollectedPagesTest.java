package io.scos.client.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.scos.client.collect.LocalContentCapture;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawItems;
import io.scos.spi.connector.RawMutation;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("수집 페이지 관찰은 원래 순서와 내용을 보존한다(D4, C7)")
class CollectedPagesTest {
  private static final Instant OBSERVED_AT = Instant.parse("2026-10-28T00:00:00Z");
  private final LocalContentCapture capture = new LocalContentCapture();

  private RawItem givenItem(String id) {
    return RawItems.item(id)
        .resourceType("test.item")
        .version("version-" + id)
        .metadata("{}")
        .observedAt(OBSERVED_AT)
        .build();
  }

  private RawItem givenJson(String json) {
    return givenItem("content").withContent(capture.json("application/json", json));
  }

  private RawMutation.Delete givenDelete(String id) {
    return new RawMutation.Delete(
        id, Optional.empty(), Optional.empty(), Optional.empty(), OBSERVED_AT);
  }

  @Nested
  @DisplayName("여러 페이지에 Upsert와 Delete가 섞여 있는 상태에서")
  class MixedPages {
    private List<CollectionPage> pages;

    @BeforeEach
    void setUpPages() {
      pages =
          List.of(
              new CollectionPage(
                  List.of(
                      givenDelete("z-deleted"),
                      new RawMutation.Upsert(givenItem("z-first")),
                      givenDelete("a-deleted")),
                  List.of(),
                  new Checkpoint(1, "{\"page\":1}")),
              new CollectionPage(
                  List.of(
                      new RawMutation.Upsert(givenItem("a-second")),
                      givenDelete("m-deleted"),
                      new RawMutation.Upsert(givenItem("m-third"))),
                  List.of(),
                  new Checkpoint(2, "{\"page\":2}")));
    }

    @Test
    @DisplayName("추가 id를 조회하면 페이지와 변경 순서를 유지한다")
    void upsertsKeepEncounterOrder() {
      // when
      List<String> ids = CollectedPages.upserts(pages);

      // then
      assertThat(ids).containsExactly("z-first", "a-second", "m-third");
    }

    @Test
    @DisplayName("삭제 id를 조회하면 페이지와 변경 순서를 유지한다")
    void deletesKeepEncounterOrder() {
      // when
      List<String> ids = CollectedPages.deletes(pages);

      // then
      assertThat(ids).containsExactly("z-deleted", "a-deleted", "m-deleted");
    }

    @Test
    @DisplayName("원본들을 조회하면 추가된 항목만 원래 순서로 반환한다")
    void itemsKeepUpsertContentsAndOrder() {
      // when
      List<RawItem> items = CollectedPages.items(pages);

      // then
      assertThat(items)
          .extracting(RawItem::externalId, RawItem::sourceVersion, RawItem::metadataJson)
          .containsExactly(
              tuple("z-first", "version-z-first", "{}"),
              tuple("a-second", "version-a-second", "{}"),
              tuple("m-third", "version-m-third", "{}"));
    }

    @Test
    @DisplayName("id로 조회하면 해당 원본을 반환한다")
    void findsRequestedItem() {
      // when
      RawItem item = CollectedPages.item(pages, "a-second");

      // then
      assertThat(item)
          .extracting(RawItem::externalId, RawItem::sourceVersion)
          .containsExactly("a-second", "version-a-second");
    }

    @Test
    @DisplayName("삭제 id로 원본을 조회하면 실패한다")
    void rejectsMissingUpsert() {
      // when·then
      assertThatThrownBy(() -> CollectedPages.item(pages, "z-deleted"))
          .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("체크포인트를 조회하면 마지막 페이지의 값을 반환한다")
    void lastCheckpointComesFromLastPage() {
      // when
      Optional<Checkpoint> checkpoint = CollectedPages.last(pages);

      // then
      assertThat(checkpoint).contains(new Checkpoint(2, "{\"page\":2}"));
    }
  }

  @Nested
  @DisplayName("수집 페이지가 없는 상태에서")
  class NoPages {
    private List<CollectionPage> pages;

    @BeforeEach
    void setUpPages() {
      pages = List.of();
    }

    @Test
    @DisplayName("추가 id를 조회하면 비어 있다")
    void noUpserts() {
      // when
      List<String> ids = CollectedPages.upserts(pages);

      // then
      assertThat(ids).isEmpty();
    }

    @Test
    @DisplayName("삭제 id를 조회하면 비어 있다")
    void noDeletes() {
      // when
      List<String> ids = CollectedPages.deletes(pages);

      // then
      assertThat(ids).isEmpty();
    }

    @Test
    @DisplayName("원본들을 조회하면 비어 있다")
    void noItems() {
      // when
      List<RawItem> items = CollectedPages.items(pages);

      // then
      assertThat(items).isEmpty();
    }

    @Test
    @DisplayName("체크포인트를 조회하면 실패한다")
    void lastCheckpointRequiresPage() {
      // when·then
      assertThatThrownBy(() -> CollectedPages.last(pages))
          .isInstanceOf(NoSuchElementException.class);
    }
  }

  @Test
  @DisplayName("콘텐츠 없는 원본을 읽으면 비어 있다")
  void readsAbsentContent() {
    // given
    RawItem item = givenItem("no-content");

    // when
    Optional<String> text = CollectedPages.read(item);

    // then
    assertThat(text).isEmpty();
  }

  @Test
  @DisplayName("인라인 원본을 읽으면 JSON 원문을 반환한다")
  void readsInlineContent() {
    // given
    RawItem item = givenJson("{ \"text\": \"한글\\n본문\" }");

    // when
    Optional<String> text = CollectedPages.read(item);

    // then
    assertThat(text).contains("{ \"text\": \"한글\\n본문\" }");
  }

  @Test
  @DisplayName("운영 캡처의 큰 JSON을 읽으면 UTF-8 원문을 반환한다")
  void readsCapturedUtf8Blob() {
    // given
    RawContent.Blob blob =
        (RawContent.Blob) capture.json("application/json", "\"" + "가".repeat(21845) + "\"");
    RawItem item = givenItem("blob").withContent(blob);

    // when
    Optional<String> text = CollectedPages.read(item);

    // then
    assertThat(text).contains("\"" + "가".repeat(21845) + "\"");
  }

  @Test
  @DisplayName("데이터가 없는 저장 후 Blob을 읽으면 실패한다")
  void rejectsStoredBlobWithoutData() {
    // given
    RawContent.Blob blob = (RawContent.Blob) capture.bytes("text/plain", new byte[] {65});
    RawItem item = givenItem("stored").withContent(blob.stored());

    // when·then
    assertThatThrownBy(() -> CollectedPages.read(item)).isInstanceOf(NoSuchElementException.class);
  }
}

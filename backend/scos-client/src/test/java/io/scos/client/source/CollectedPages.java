package io.scos.client.source;

import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** 어댑터가 낸 페이지의 관찰: 변경 id·원본 조회·캡처 내용 읽기를 한 곳에서 정의한다. */
public final class CollectedPages {
  private CollectedPages() {}

  public static List<String> upserts(List<CollectionPage> pages) {
    return pages.stream()
        .flatMap(page -> page.mutations().stream())
        .filter(RawMutation.Upsert.class::isInstance)
        .map(RawMutation::externalId)
        .toList();
  }

  public static List<String> deletes(List<CollectionPage> pages) {
    return pages.stream()
        .flatMap(page -> page.mutations().stream())
        .filter(RawMutation.Delete.class::isInstance)
        .map(RawMutation::externalId)
        .toList();
  }

  public static List<RawItem> items(List<CollectionPage> pages) {
    return pages.stream()
        .flatMap(page -> page.mutations().stream())
        .filter(RawMutation.Upsert.class::isInstance)
        .map(mutation -> ((RawMutation.Upsert) mutation).item())
        .toList();
  }

  public static RawItem item(List<CollectionPage> pages, String externalId) {
    return items(pages).stream()
        .filter(item -> item.externalId().equals(externalId))
        .findFirst()
        .orElseThrow();
  }

  public static Optional<Checkpoint> last(List<CollectionPage> pages) {
    return Optional.of(pages.getLast().nextCheckpoint());
  }

  public static Optional<String> read(RawItem item) {
    return item.content()
        .map(
            content ->
                switch (content) {
                  case RawContent.Inline inline -> inline.valueJson();
                  case RawContent.Blob blob ->
                      new String(blob.data().orElseThrow(), StandardCharsets.UTF_8);
                });
  }
}

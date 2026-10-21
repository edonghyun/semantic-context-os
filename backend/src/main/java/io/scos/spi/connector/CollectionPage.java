package io.scos.spi.connector;

import java.util.List;

/** 어댑터가 낸 페이지(TS CollectionPage): 변경·거부 항목과 그 뒤의 재개 위치. 커밋은 셋을 함께만 반영한다(F2). */
public record CollectionPage(
    List<RawMutation> mutations, List<RejectedItem> rejections, Checkpoint nextCheckpoint) {
  public CollectionPage {
    mutations = mutations == null ? List.of() : List.copyOf(mutations);
    rejections = rejections == null ? List.of() : List.copyOf(rejections);
    if (nextCheckpoint == null)
      throw new IllegalArgumentException("nextCheckpoint must not be null");
  }

  /** upsert만 있는 페이지(테스트·실험). */
  public static CollectionPage ofItems(List<RawItem> items, Checkpoint next) {
    return new CollectionPage(
        items.stream().<RawMutation>map(RawMutation.Upsert::new).toList(), List.of(), next);
  }
}

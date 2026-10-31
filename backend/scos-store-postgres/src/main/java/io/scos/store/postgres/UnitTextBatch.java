package io.scos.store.postgres;

import io.scos.engine.support.text.Text;
import io.scos.spi.store.IndexedText;
import io.scos.store.postgres.internal.Bigrams;
import java.util.ArrayList;
import java.util.List;

/**
 * 키워드 적재 묶음 하나의 배열 바인드(unnest): unit_texts 열(원소 i = 묶음의 i번째 유닛)과 펼친 bigram 행(유닛 순서, 유닛 안은 {@link
 * Bigrams#bigramCounts} 순서). 열 배열은 한 번에 함께 채워 서로 어긋나지 않는다.
 */
final class UnitTextBatch {
  private final String[] unitIds;
  private final String[] documentIds;
  private final String[] partitions;
  private final Boolean[] archived;
  private final String[] texts;
  private final String[] compactTexts;
  private final List<String> bigramUnits = new ArrayList<>();
  private final List<String> bigrams = new ArrayList<>();
  private final List<Integer> counts = new ArrayList<>();

  private UnitTextBatch(int size) {
    unitIds = new String[size];
    documentIds = new String[size];
    partitions = new String[size];
    archived = new Boolean[size];
    texts = new String[size];
    compactTexts = new String[size];
  }

  static UnitTextBatch of(List<IndexedText> chunk) {
    UnitTextBatch batch = new UnitTextBatch(chunk.size());
    for (int i = 0; i < chunk.size(); i++) batch.put(i, chunk.get(i));
    return batch;
  }

  private void put(int i, IndexedText u) {
    String text = Bigrams.normalize(u);
    unitIds[i] = u.unitId();
    documentIds[i] = u.documentId();
    partitions[i] = u.partition();
    archived[i] = u.archived();
    texts[i] = text;
    compactTexts[i] = Text.stripWhitespace(text);
    Bigrams.bigramCounts(text)
        .forEach(
            (bigram, count) -> {
              bigramUnits.add(u.unitId());
              bigrams.add(bigram);
              counts.add(count);
            });
  }

  String[] unitIds() {
    return unitIds;
  }

  String[] documentIds() {
    return documentIds;
  }

  String[] partitions() {
    return partitions;
  }

  Boolean[] archived() {
    return archived;
  }

  String[] texts() {
    return texts;
  }

  String[] compactTexts() {
    return compactTexts;
  }

  boolean hasBigrams() {
    return !bigrams.isEmpty();
  }

  String[] bigramUnits() {
    return bigramUnits.toArray(String[]::new);
  }

  String[] bigrams() {
    return bigrams.toArray(String[]::new);
  }

  Integer[] counts() {
    return counts.toArray(Integer[]::new);
  }
}

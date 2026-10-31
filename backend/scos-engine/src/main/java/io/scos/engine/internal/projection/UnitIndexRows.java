package io.scos.engine.internal.projection;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.Tier;
import java.util.ArrayList;
import java.util.List;

/**
 * 유닛 묶음의 색인 행: 키워드 행은 유닛마다 하나, 벡터 행은 임베딩이 있는 유닛만. 임베딩이 없는 유닛 id는 따로 모은다(그 벡터 행을 지울 때 쓴다). archived는
 * tier가 ARCHIVED인 유닛이다.
 */
record UnitIndexRows(
    List<IndexedText> texts, List<IndexedUnit> vectors, List<String> withoutVector) {
  static UnitIndexRows of(List<ContextUnit> units) {
    List<IndexedText> texts = new ArrayList<>(units.size());
    List<IndexedUnit> vectors = new ArrayList<>();
    List<String> withoutVector = new ArrayList<>();
    for (ContextUnit u : units) {
      boolean archived = u.tier() == Tier.ARCHIVED;
      texts.add(
          new IndexedText(u.id(), u.documentId(), u.title(), u.content(), u.partition(), archived));
      if (u.embedding().isPresent())
        vectors.add(
            new IndexedUnit(u.id(), u.documentId(), u.partition(), archived, u.embedding().get()));
      else withoutVector.add(u.id());
    }
    return new UnitIndexRows(texts, vectors, withoutVector);
  }
}

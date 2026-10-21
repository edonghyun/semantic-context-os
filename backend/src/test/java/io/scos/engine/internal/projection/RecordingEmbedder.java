package io.scos.engine.internal.projection;

import io.scos.spi.embedding.Embedder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * DocumentIngestor·CachedEmbeddings 테스트의 결정론 임베더({@link HashVectors}): 입력마다 다른 벡터를 내고 호출 입력을 기록한다.
 * 실패는 이름 있는 메서드로만 켠다(TESTING §3.5 — boolean 스위치를 본문에 노출하지 않는다).
 */
final class RecordingEmbedder implements Embedder {
  final List<String> calls = new ArrayList<>();
  private Predicate<List<String>> failsOn = texts -> false;

  /** 다음 embed부터 429 실패를 던진다. */
  void failsWith429() {
    failsOn = texts -> true;
  }

  /** 다음 embed부터 이 조각을 담은 입력이 있는 호출만 429로 실패한다(문서 하나만 실패시킨다). */
  void failsWith429On(String fragment) {
    failsOn = texts -> texts.stream().anyMatch(t -> t.contains(fragment));
  }

  /** 다음 embed부터 다시 벡터를 낸다. */
  void recovers() {
    failsOn = texts -> false;
  }

  /** 지금까지 기록한 호출 입력을 잊는다 — 준비 단계의 임베딩을 when의 호출과 섞지 않게. */
  void forgetCalls() {
    calls.clear();
  }

  @Override
  public int dim() {
    return HashVectors.DIM;
  }

  @Override
  public List<double[]> embed(List<String> texts) {
    if (failsOn.test(texts)) throw new EmbedderException("injected 429");
    calls.addAll(texts);
    return texts.stream().map(RecordingEmbedder::vectorOf).toList();
  }

  /** 이 입력에 돌려주는 벡터 — 입력마다 다르다. */
  static double[] vectorOf(String input) {
    return HashVectors.of(input);
  }
}

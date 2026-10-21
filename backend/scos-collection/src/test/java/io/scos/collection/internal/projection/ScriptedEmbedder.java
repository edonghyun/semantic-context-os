package io.scos.collection.internal.projection;

import static io.scos.collection.internal.projection.ProjectionScenario.SENTINEL;

import io.scos.engine.internal.projection.HashVectors;
import io.scos.spi.embedding.Embedder;
import io.scos.support.HoldGate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** ProjectionScenario의 결정론 임베더(HashVectors). 행동은 시나리오 메서드로만 바꾼다. */
final class ScriptedEmbedder implements Embedder {
  final AtomicInteger calls = new AtomicInteger();
  volatile boolean fail;

  /** 이 조각을 담은 입력이 있는 호출만 실패한다(null이면 끔). */
  volatile String failOn;

  volatile boolean sentinel;
  volatile Runnable onEachEmbed = () -> {};
  volatile Runnable onNextEmbed = () -> {};
  volatile HoldGate gate;

  void onNextEmbed(Runnable action) {
    onNextEmbed =
        () -> {
          onNextEmbed = () -> {};
          action.run();
        };
  }

  @Override
  public int dim() {
    return HashVectors.DIM;
  }

  @Override
  public List<double[]> embed(List<String> texts) {
    calls.incrementAndGet();
    onNextEmbed.run();
    onEachEmbed.run();
    String fragment = failOn;
    if (fail || (fragment != null && texts.stream().anyMatch(t -> t.contains(fragment))))
      throw new EmbedderException("Gemini embeddings 429", 429);
    List<double[]> out = new ArrayList<>();
    for (String t : texts)
      out.add(sentinel ? new double[] {SENTINEL, SENTINEL, SENTINEL, SENTINEL} : HashVectors.of(t));
    return out;
  }
}

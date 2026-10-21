package io.scos.engine.internal.projection;

import io.scos.engine.support.text.Text;

/** 결정론 4차원 벡터: 본문 sha256의 앞 3바이트와 1. 같은 본문은 같은 벡터, 다른 본문은 (거의) 다른 벡터다. */
public final class HashVectors {
  public static final int DIM = 4;

  private HashVectors() {}

  public static double[] of(String text) {
    String h = Text.sha256Hex(text);
    return new double[] {
      Integer.parseInt(h.substring(0, 2), 16),
      Integer.parseInt(h.substring(2, 4), 16),
      Integer.parseInt(h.substring(4, 6), 16),
      1
    };
  }
}

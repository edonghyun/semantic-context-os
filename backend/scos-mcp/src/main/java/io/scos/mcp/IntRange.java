package io.scos.mcp;

import java.util.Optional;

/** 도구 인자의 정수 범위. 양끝은 비어 있으면 열려 있다. 거부(인자 검사)와 끼워 넣기(clamp) 양쪽에 쓴다. */
record IntRange(Optional<Integer> min, Optional<Integer> max) {
  static IntRange any() {
    return new IntRange(Optional.empty(), Optional.empty());
  }

  static IntRange atLeast(int min) {
    return new IntRange(Optional.of(min), Optional.empty());
  }

  static IntRange between(int min, int max) {
    return new IntRange(Optional.of(min), Optional.of(max));
  }

  /** 범위 안으로 끼워 넣는다(양끝이 열려 있으면 그쪽은 그대로). */
  int clamp(int value) {
    int lower = min.map(m -> Math.max(m, value)).orElse(value);
    return max.map(m -> Math.min(m, lower)).orElse(lower);
  }
}

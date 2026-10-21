package io.scos.spi.projection;

/** 토큰 계산 포트(CONTRACTS F-6·C4, ADR-0019): 유닛 분할 한도·최소 토큰·importance·예산의 단위. 구현은 TS와 같은 값을 내야 한다. */
public interface Tokenizer {
  int count(String text);
}

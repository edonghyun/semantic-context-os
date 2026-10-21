package io.scos.engine.internal.search;

import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 규모 프로브용 합성 코퍼스(F3 T3.2, ts-replacement-gap G9·G10). 실데이터를 쓰지 않는다. 운영 분포를 흉내 낸다: 유닛의 {@link
 * #SHEET_SHARE}가 Drive 시트형(문서당 {@link #UNITS_PER_SHEET}유닛, "A12: 값" 셀 몇 개의 짧은 유닛)이고 나머지는 산문형(문서당
 * {@link #UNITS_PER_PROSE}유닛, 단어 수십 개, 다른 산문 문서 링크 2개)이다. 단어는 {@link #VOCABULARY_RESOURCE}(입력 없이 자모
 * 가중치·조사·Zipf 공식으로 만든 합성 어휘와 등장 횟수, tools/make-korean-vocabulary.py)에서 등장 횟수에 비례해 뽑는다 — 음절 약
 * 890개·단어 안 bigram 약 1만 종이라 실제 한국어 기술 문서의 bigram 규모를 따른다(F3 리뷰 I4: 음절 50개 합성 어휘는 bigram이 약
 * 2,500종뿐이라 포스팅 목록이 몇 배 길었다). 시트 행의 셀 수({@link #CELLS_PER_ROW})는 운영 통계로 맞추지 못한 가정이다(운영 DB 접근 금지).
 *
 * <p>임베딩은 주제 중심 벡터 + 잡음이다. 같은 주제의 유닛·질의 사이 코사인이 약 0.7이라 벡터 채널이 최소 점수(0.5)를 넘는 후보를 낸다 — 무작위 768차원
 * 벡터는 서로 거의 직교해 벡터 채널이 비어 버린다. 시드가 고정이라 같은 크기면 같은 코퍼스다.
 */
final class SyntheticCorpus {
  static final double SHEET_SHARE = 0.91;
  static final int UNITS_PER_SHEET = 300;
  static final int UNITS_PER_PROSE = 10;
  static final int DIM = 768;
  static final int TOPICS = 64;
  static final String VOCABULARY_RESOURCE = "/perf/korean-vocabulary.tsv";
  static final int PROSE_WORDS = 60;
  static final int CELLS_PER_ROW = 4;
  static final int LINKS_PER_PROSE_UNIT = 2;
  static final long SEED = 20261015L;

  /** 잡음 성분의 표준편차: 1/(1 + σ²·DIM) ≈ 0.7이 되는 값(같은 주제 코사인). */
  static final double NOISE = Math.sqrt((1 / 0.7 - 1) / DIM);

  private final int sheetUnits;
  private final int proseUnits;
  private final String[] words;
  private final double[] wordCdf;
  private final List<String> queryWords;
  private final double[][] topics;

  SyntheticCorpus(int totalUnits) {
    this.sheetUnits = (int) Math.round(totalUnits * SHEET_SHARE);
    this.proseUnits = totalUnits - sheetUnits;
    SplittableRandom random = new SplittableRandom(SEED);
    List<String[]> rows = vocabulary();
    this.words = rows.stream().map(r -> r[0]).toArray(String[]::new);
    this.wordCdf = cdf(rows.stream().mapToLong(r -> Long.parseLong(r[1])).toArray());
    this.queryWords = rows.stream().map(r -> r[0]).filter(w -> w.length() >= 2).toList();
    this.topics = new double[TOPICS][];
    for (int t = 0; t < TOPICS; t++) topics[t] = normalized(gaussian(random, 1));
  }

  int totalUnits() {
    return sheetUnits + proseUnits;
  }

  int proseDocuments() {
    return ceilDiv(proseUnits, UNITS_PER_PROSE);
  }

  int sheetDocuments() {
    return ceilDiv(sheetUnits, UNITS_PER_SHEET);
  }

  int documents() {
    return proseDocuments() + sheetDocuments();
  }

  /** 문서 하나의 유닛(적재 단위). 산문 문서가 먼저, 시트 문서가 뒤다. */
  List<ContextUnit> document(int index) {
    SplittableRandom random = new SplittableRandom(SEED ^ (index * 0x9E3779B97F4A7C15L));
    return index < proseDocuments()
        ? proseDocument(index, random)
        : sheetDocument(index - proseDocuments(), random);
  }

  private List<ContextUnit> proseDocument(int index, SplittableRandom random) {
    String doc = "prose-" + index;
    int count = Math.min(UNITS_PER_PROSE, proseUnits - index * UNITS_PER_PROSE);
    String[] links = new String[LINKS_PER_PROSE_UNIT];
    for (int l = 0; l < links.length; l++) links[l] = "prose-" + random.nextInt(proseDocuments());
    List<ContextUnit> units = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      StringBuilder content = new StringBuilder();
      for (int w = 0; w < PROSE_WORDS; w++) content.append(w == 0 ? "" : " ").append(word(random));
      units.add(
          ContextUnits.unit(doc + "-" + i)
              .inDocument(doc)
              .titled("문서 " + word(random) + " " + index)
              .content(content.toString())
              .tokens(PROSE_WORDS * 2)
              .linksTo(links)
              .embedding(embedding(index, random))
              .build());
    }
    return units;
  }

  private List<ContextUnit> sheetDocument(int index, SplittableRandom random) {
    String doc = "sheet-" + index;
    int count = Math.min(UNITS_PER_SHEET, sheetUnits - index * UNITS_PER_SHEET);
    String title = "시트 " + word(random) + " " + index;
    List<ContextUnit> units = new ArrayList<>(count);
    for (int row = 1; row <= count; row++) {
      StringBuilder content = new StringBuilder();
      for (int c = 0; c < CELLS_PER_ROW; c++) {
        if (c > 0) content.append('\n');
        content.append((char) ('A' + c)).append(row).append(": ");
        content.append(
            random.nextBoolean() ? word(random) : String.valueOf(random.nextInt(100_000)));
      }
      units.add(
          ContextUnits.unit(doc + "-" + row)
              .inDocument(doc)
              .titled(title + " / 범위 A" + row + ":D" + row)
              .content(content.toString())
              .tokens(CELLS_PER_ROW * 4)
              .embedding(embedding(index, random))
              .build());
    }
    return units;
  }

  /** 측정 질의: 2글자 이상 어휘의 흔한 단어·중간·드문 단어를 한·두·세 단어로 섞는다. 같은 n이면 같은 질의 목록이다. */
  List<String> queries(int n) {
    int[] ranks = {0, 5, 20, 80, 300, 1_000, 3_000, 8_000};
    List<String> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      String first = queryWord(ranks[i % ranks.length]);
      out.add(
          switch (i % 3) {
            case 0 -> first;
            case 1 -> first + " " + queryWord(ranks[(i + 3) % ranks.length]);
            default ->
                first + " " + queryWord(ranks[(i + 1) % ranks.length]) + " " + queryWord(i * 7);
          });
    }
    return out;
  }

  private String queryWord(int rank) {
    return queryWords.get(Math.min(rank, queryWords.size() - 1));
  }

  /** 질의 텍스트의 임베딩: 텍스트 해시로 고른 주제 중심 + 잡음. */
  double[] queryEmbedding(String text) {
    SplittableRandom random = new SplittableRandom(SEED ^ text.hashCode());
    return embedding(Math.floorMod(text.hashCode(), TOPICS), random);
  }

  private double[] embedding(int topicSeed, SplittableRandom random) {
    double[] center = topics[Math.floorMod(topicSeed, TOPICS)];
    double[] v = gaussian(random, NOISE);
    for (int i = 0; i < DIM; i++) v[i] += center[i];
    return v;
  }

  private String word(SplittableRandom random) {
    int rank = Arrays.binarySearch(wordCdf, random.nextDouble());
    return words[Math.min(rank < 0 ? -rank - 1 : rank, words.length - 1)];
  }

  /** 단어와 등장 횟수(등장 횟수 내림차순). 주석 줄은 건너뛴다. */
  private static List<String[]> vocabulary() {
    try (InputStream in = SyntheticCorpus.class.getResourceAsStream(VOCABULARY_RESOURCE)) {
      if (in == null) throw new IllegalStateException("어휘 자원이 없다: " + VOCABULARY_RESOURCE);
      return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .lines()
          .filter(line -> !line.startsWith("#") && !line.isBlank())
          .map(line -> line.split("\t"))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static double[] cdf(long[] counts) {
    double[] cdf = new double[counts.length];
    double sum = 0;
    for (int i = 0; i < counts.length; i++) cdf[i] = sum += counts[i];
    for (int i = 0; i < counts.length; i++) cdf[i] /= sum;
    return cdf;
  }

  private static double[] gaussian(SplittableRandom random, double sigma) {
    double[] v = new double[DIM];
    for (int i = 0; i < DIM; i += 2) {
      double u1 = 1 - random.nextDouble();
      double u2 = random.nextDouble();
      double r = sigma * Math.sqrt(-2 * Math.log(u1));
      v[i] = r * Math.cos(2 * Math.PI * u2);
      if (i + 1 < DIM) v[i + 1] = r * Math.sin(2 * Math.PI * u2);
    }
    return v;
  }

  private static double[] normalized(double[] v) {
    double norm = 0;
    for (double x : v) norm += x * x;
    norm = Math.sqrt(norm);
    for (int i = 0; i < v.length; i++) v[i] /= norm;
    return v;
  }

  private static int ceilDiv(int a, int b) {
    return (a + b - 1) / b;
  }
}

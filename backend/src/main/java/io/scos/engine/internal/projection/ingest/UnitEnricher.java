package io.scos.engine.internal.projection.ingest;

import io.scos.engine.support.text.Text;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.Provenance;
import io.scos.spi.store.Tier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Content Enricher(TS core/ingestion/unit-enricher.ts, G4): 세그먼트에 id·출처·중요도·링크를 입혀 ContextUnit으로
 * 완성한다(augment 없음).
 */
final class UnitEnricher {
  private static final Pattern MD_LINK =
      Pattern.compile("\\[[^\\]]*\\]\\(([^)" + Text.whitespaceChars() + "]+)\\)");
  private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
  private static final Pattern ANCHOR = Pattern.compile("#.*");
  private static final Pattern SCHEME = Pattern.compile("^[a-z]+://", Pattern.CASE_INSENSITIVE);
  private static final Pattern PATH_SEP = Pattern.compile(" > ");

  List<ContextUnit> apply(List<Segment> segments, SourceDocument doc) {
    // 동일 내용 제거 후의 경로별 순번. 문서마다 초기화하고 본문 수정에는 영향받지 않는다.
    Map<String, Integer> occurrences = new HashMap<>();
    List<ContextUnit> out = new ArrayList<>(segments.size());
    for (Segment seg : segments) {
      int occurrence = occurrences.merge(seg.headingPath(), 1, Integer::sum);
      out.add(enrich(seg, doc, occurrence));
    }
    return out;
  }

  private static ContextUnit enrich(Segment seg, SourceDocument doc, int occurrence) {
    return new ContextUnit(
        UnitIds.make(doc.sourceId(), doc.documentId(), seg.headingPath(), occurrence),
        seg.partition(),
        seg.headingPath(), // 브레드크럼 제목(T12.2)
        seg.content(),
        Text.sha256Hex(seg.content()),
        seg.tokens(),
        Tier.RAW,
        new Provenance(doc.sourceId(), doc.documentId(), doc.uri(), doc.updatedAt()),
        scoreImportance(seg.headingPath(), seg.tokens()),
        Optional.empty(),
        Optional.empty(),
        extractLinks(seg.content(), doc.documentId()),
        seg.tags(),
        List.of(),
        Optional.empty(),
        List.of());
  }

  /** 마크다운 링크·위키링크를 문서 기준 상대 경로(documentId)로 정규화해 수집한다(앵커 제거·외부 스킴 제외·자기 제외·정렬). */
  static List<String> extractLinks(String content, String documentId) {
    String dir = PosixPaths.dirname(documentId);
    TreeSet<String> targets = new TreeSet<>(); // String.compareTo = UTF-16 코드 유닛 순 = JS 기본 sort
    Matcher md = MD_LINK.matcher(content);
    while (md.find()) {
      String href = ANCHOR.matcher(md.group(1)).replaceFirst("");
      if (href.isEmpty() || SCHEME.matcher(href).find()) continue;
      targets.add(PosixPaths.normalize(PosixPaths.join(dir, href)));
    }
    Matcher wiki = WIKI_LINK.matcher(content);
    while (wiki.find()) {
      String target = ANCHOR.matcher(Text.jsTrim(wiki.group(1))).replaceFirst("");
      if (target.isEmpty()) continue;
      targets.add(PosixPaths.normalize(PosixPaths.join(dir, target)));
    }
    targets.remove(documentId);
    return List.copyOf(targets);
  }

  static int scoreImportance(String headingPath, int tokens) {
    int score = 5;
    int depth = PATH_SEP.split(headingPath, -1).length;
    if (depth <= 2) score += 2;
    if (tokens > 200) score += 1;
    if (tokens < 30) score -= 2;
    return Math.max(1, Math.min(10, score));
  }
}

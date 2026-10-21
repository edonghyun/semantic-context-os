package io.scos.engine.internal.projection.ingest;

import io.scos.engine.support.text.Text;
import io.scos.spi.projection.SourceDocument;
import io.scos.spi.projection.Tokenizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 헤딩 분할(TS core/ingestion/heading-segmenter.ts, G3·C4): `#`~`######` 경계·경로 스택·펜스 무시, 800 토큰 초과는 문단
 * → 행 → 반분으로 `(part n)`. 4096자 초과 조각은 토크나이저에 넘기지 않는다.
 */
final class HeadingSegmenter {
  static final int DEFAULT_MAX_TOKENS = 800;
  static final int MAX_TOKENIZER_CHARS = 4096;
  private static final Pattern HEADING =
      Pattern.compile("^(#{1,6})" + Text.whitespaceClass() + "+(.*)$", Pattern.DOTALL);
  private static final Pattern NEWLINE = Pattern.compile("\n");
  private static final Pattern PARAGRAPH = Pattern.compile("\n\n+");

  private final Tokenizer tokenizer;
  private final int maxTokens;

  HeadingSegmenter(Tokenizer tokenizer, int maxTokens) {
    this.tokenizer = tokenizer;
    this.maxTokens = maxTokens;
  }

  List<Segment> apply(SourceDocument doc) {
    Frontmatter fm = Frontmatter.parse(doc.body());
    List<Segment> segments = new ArrayList<>();
    for (RawSection raw : splitByHeadings(fm.content(), doc.title())) {
      String text = Text.jsTrim(String.join("\n", raw.lines));
      String base = String.join(" > ", raw.path);
      Optional<Measured> measured = measure(text);
      if (measured.isPresent()) {
        segments.add(segment(base, raw.title, measured.get(), fm));
        continue;
      }
      splitOversized(text, base, raw.title, fm, segments);
    }
    return segments;
  }

  private static Segment segment(String headingPath, String title, Measured m, Frontmatter fm) {
    return new Segment(
        headingPath, title, m.content, m.tokens, fm.tags(), "/knowledge", fm.partition());
  }

  private static List<RawSection> splitByHeadings(String body, String docTitle) {
    List<RawSection> raws = new ArrayList<>();
    RawSection current = new RawSection(List.of(docTitle), docTitle);
    List<Heading> stack = new ArrayList<>();
    CodeFenceTracker fences = new CodeFenceTracker();
    for (String line : NEWLINE.split(body, -1)) {
      if (fences.consume(line)) {
        current.lines.add(line);
        continue;
      }
      Matcher m = HEADING.matcher(line);
      if (!m.matches()) {
        current.lines.add(line);
        continue;
      }
      if (current.hasContent()) raws.add(current);
      int level = m.group(1).length();
      String text = Text.jsTrim(m.group(2));
      // 동급 이상 헤딩을 만나면 스택을 되감아 경로가 실제 문서 계층을 따르게 한다
      while (!stack.isEmpty() && stack.get(stack.size() - 1).level >= level)
        stack.remove(stack.size() - 1);
      stack.add(new Heading(level, text));
      current = new RawSection(stack.stream().map(h -> h.text).toList(), text);
      current.lines.add(line);
    }
    if (current.hasContent()) raws.add(current);
    return raws;
  }

  /** 문단 경계를 우선하고, 긴 문단은 행·문자 경계에서 한도 안으로 나눈다. */
  private void splitOversized(
      String text, String base, String title, Frontmatter fm, List<Segment> out) {
    Measured[] buf = {null};
    int[] part = {1};
    Runnable flush =
        () -> {
          if (buf[0] == null) return;
          out.add(
              segment(
                  base + " (part " + part[0] + ")", title + " (part " + part[0] + ")", buf[0], fm));
          part[0]++;
          buf[0] = null;
        };
    for (String p : PARAGRAPH.split(text, -1)) {
      Optional<Measured> measured = measure(p);
      if (measured.isEmpty()) {
        flush.run();
        List<Measured> chunks = new ArrayList<>();
        splitLongParagraph(p, chunks);
        for (Measured chunk : chunks) {
          buf[0] = chunk;
          flush.run();
        }
        continue;
      }
      Optional<Measured> combined =
          buf[0] != null ? measure(buf[0].content + "\n\n" + p) : measured;
      if (combined.isEmpty()) flush.run();
      buf[0] = combined.orElse(measured.get());
    }
    flush.run();
  }

  private Optional<Measured> measure(String content) {
    if (content.length() > MAX_TOKENIZER_CHARS) return Optional.empty();
    int tokens = tokenizer.count(content);
    return tokens <= maxTokens ? Optional.of(new Measured(content, tokens)) : Optional.empty();
  }

  private void splitLongParagraph(String text, List<Measured> out) {
    Optional<Measured> measured = measure(text);
    if (measured.isPresent()) {
      out.add(measured.get());
      return;
    }
    int middle = text.length() / 2;
    int newline = text.lastIndexOf('\n', middle);
    if (newline >= text.length() / 4) middle = newline + 1;
    // 이모지 등 보조 문자의 UTF-16 서로게이트 쌍은 나누지 않는다.
    if (middle < text.length() && Character.isLowSurrogate(text.charAt(middle))) middle -= 1;
    if (middle == 0)
      throw new IllegalStateException("Token budget cannot contain a single Unicode character");
    splitLongParagraph(text.substring(0, middle), out);
    splitLongParagraph(text.substring(middle), out);
  }

  private record Measured(String content, int tokens) {}

  private record Heading(int level, String text) {}

  private static final class RawSection {
    final List<String> path;
    final String title;
    final List<String> lines = new ArrayList<>();

    RawSection(List<String> path, String title) {
      this.path = path;
      this.title = title;
    }

    boolean hasContent() {
      return lines.stream().anyMatch(l -> !Text.jsTrim(l).isEmpty());
    }
  }
}

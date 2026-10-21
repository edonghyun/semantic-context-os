package io.scos.engine.internal.projection.ingest;

import io.scos.engine.support.text.Text;
import io.scos.spi.projection.SourceDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 본문 정규화(TS core/ingestion/body-normalizer.ts, G3): frontmatter 블록은 원문 보존, 펜스 밖 구간만 주석 제거·행끝 공백(JS
 * trimEnd)·빈 줄 축약. CRLF·CR → LF.
 */
final class BodyNormalizer {
  /** TS FRONTMATTER = /^---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/ — `$`는 입력 끝(JS)이라 \z. */
  static final Pattern FRONTMATTER =
      Pattern.compile("^---\\r?\\n[\\s\\S]*?\\r?\\n---(?:\\r?\\n|\\z)");

  private static final Pattern NEWLINE = Pattern.compile("\n");
  private static final Pattern COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
  private static final Pattern BLANK_RUN = Pattern.compile("\n{3,}");
  private static final Pattern LEADING_NL = Pattern.compile("^\n+");
  private static final Pattern TRAILING_NL = Pattern.compile("\n+\\z");

  SourceDocument apply(SourceDocument doc) {
    Matcher m = FRONTMATTER.matcher(doc.body());
    String frontmatter = m.lookingAt() ? m.group() : "";
    return doc.withBody(frontmatter + normalize(doc.body().substring(frontmatter.length())));
  }

  private String normalize(String body) {
    String[] lines = NEWLINE.split(body.replace("\r\n", "\n").replace("\r", "\n"), -1);
    CodeFenceTracker tracker = new CodeFenceTracker();
    List<Part> parts = new ArrayList<>();
    for (String line : lines) {
      boolean fenced = tracker.consume(line);
      Part tail = parts.isEmpty() ? null : parts.get(parts.size() - 1);
      if (tail != null && tail.fenced == fenced) tail.lines.add(line);
      else {
        Part p = new Part(fenced);
        p.lines.add(line);
        parts.add(p);
      }
    }
    List<String> out = new ArrayList<>(parts.size());
    for (Part p : parts) {
      String joined = String.join("\n", p.lines);
      out.add(p.fenced ? joined : normalizeProse(joined));
    }
    String result = String.join("\n", out);
    result = LEADING_NL.matcher(result).replaceFirst("");
    return TRAILING_NL.matcher(result).replaceFirst("");
  }

  private static String normalizeProse(String text) {
    String[] lines = NEWLINE.split(COMMENT.matcher(text).replaceAll(""), -1);
    List<String> trimmed = new ArrayList<>(lines.length);
    for (String l : lines) trimmed.add(Text.jsTrimEnd(l));
    String joined = BLANK_RUN.matcher(String.join("\n", trimmed)).replaceAll("\n\n");
    joined = LEADING_NL.matcher(joined).replaceFirst("\n"); // 펜스 경계와 만나는 빈 줄은 1개로
    return TRAILING_NL.matcher(joined).replaceFirst("\n");
  }

  private static final class Part {
    final boolean fenced;
    final List<String> lines = new ArrayList<>();

    Part(boolean fenced) {
      this.fenced = fenced;
    }
  }
}

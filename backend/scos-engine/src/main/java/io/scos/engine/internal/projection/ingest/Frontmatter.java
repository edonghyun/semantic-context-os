package io.scos.engine.internal.projection.ingest;

import io.scos.engine.support.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gray-matter의 부분집합(TS heading-segmenter.ts:30-33가 쓰는 만큼): 선두 `---` 블록을 떼고 `tags`(flow `[a, b]` 또는
 * 블록 목록)·`partition` 스칼라만 읽는다. BOM은 제거. 그 밖의 YAML은 무시한다(재검토: local-md 소스 추가 시, ADR-0018 §6).
 */
record Frontmatter(List<String> tags, Optional<String> partition, String content) {
  private static final Pattern NEWLINE = Pattern.compile("\\r?\\n");
  private static final Pattern COMMA = Pattern.compile(",");

  static Frontmatter parse(String body) {
    String text = body.startsWith("﻿") ? body.substring(1) : body;
    Matcher m = BodyNormalizer.FRONTMATTER.matcher(text);
    if (!m.lookingAt()) return new Frontmatter(List.of(), Optional.empty(), text);
    String block = m.group();
    String[] lines = NEWLINE.split(block, -1);
    List<String> tags = new ArrayList<>();
    Optional<String> partition = Optional.empty();
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      if (line.startsWith("tags:")) {
        String rest = Text.jsTrim(line.substring("tags:".length()));
        if (rest.startsWith("[") && rest.endsWith("]")) {
          for (String t : COMMA.split(rest.substring(1, rest.length() - 1), -1)) {
            String v = unquote(Text.jsTrim(t));
            if (!v.isEmpty()) tags.add(v);
          }
        } else if (rest.isEmpty()) {
          for (int j = i + 1; j < lines.length; j++) {
            String item = Text.jsTrim(lines[j]);
            if (!item.startsWith("- ")) break;
            tags.add(unquote(Text.jsTrim(item.substring(2))));
          }
        }
      } else if (line.startsWith("partition:")) {
        partition = Optional.of(unquote(Text.jsTrim(line.substring("partition:".length()))));
      }
    }
    return new Frontmatter(List.copyOf(tags), partition, text.substring(block.length()));
  }

  private static String unquote(String v) {
    if (v.length() >= 2
        && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))))
      return v.substring(1, v.length() - 1);
    return v;
  }
}

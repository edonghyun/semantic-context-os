package io.scos.engine.internal.projection.ingest;

import io.scos.engine.support.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gray-matter의 부분집합(헤딩 분할기가 쓰는 만큼): 선두 `---` 블록을 떼고 `tags`(flow `[a, b]` 또는 블록 목록)·`partition` 스칼라만
 * 읽는다. BOM은 제거. 그 밖의 YAML은 무시한다(재검토: local-md 소스 추가 시, ADR-0018 §6).
 */
record Frontmatter(List<String> tags, Optional<String> partition, String content) {
  private static final Pattern NEWLINE = Pattern.compile("\\r?\\n");
  private static final Pattern COMMA = Pattern.compile(",");
  private static final String TAGS = "tags:";
  private static final String PARTITION = "partition:";

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
      if (line.startsWith(TAGS)) tags.addAll(tagsFrom(valueOf(line, TAGS), lines, i + 1));
      else if (line.startsWith(PARTITION))
        partition = Optional.of(unquote(valueOf(line, PARTITION)));
    }
    return new Frontmatter(List.copyOf(tags), partition, text.substring(block.length()));
  }

  private static String valueOf(String line, String key) {
    return Text.jsTrim(line.substring(key.length()));
  }

  /** `tags:` 뒤의 값: flow 목록 `[a, b]`, 비었으면 다음 줄부터의 블록 목록, 그 밖(스칼라)은 태그 없음. */
  private static List<String> tagsFrom(String value, String[] lines, int next) {
    if (value.startsWith("[") && value.endsWith("]")) return flowTags(value);
    if (value.isEmpty()) return blockTags(lines, next);
    return List.of();
  }

  /** 쉼표로 나누고 따옴표를 벗긴다. 빈 항목은 버린다. */
  private static List<String> flowTags(String value) {
    List<String> tags = new ArrayList<>();
    for (String t : COMMA.split(value.substring(1, value.length() - 1), -1)) {
      String v = unquote(Text.jsTrim(t));
      if (!v.isEmpty()) tags.add(v);
    }
    return tags;
  }

  /** `- `로 시작하는 줄이 이어지는 동안. */
  private static List<String> blockTags(String[] lines, int from) {
    List<String> tags = new ArrayList<>();
    for (int j = from; j < lines.length; j++) {
      String item = Text.jsTrim(lines[j]);
      if (!item.startsWith("- ")) break;
      tags.add(unquote(Text.jsTrim(item.substring(2))));
    }
    return tags;
  }

  private static String unquote(String v) {
    if (v.length() >= 2
        && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))))
      return v.substring(1, v.length() - 1);
    return v;
  }
}

package io.scos.engine.internal.projection.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("머리말은 tags·partition만 읽고 블록을 본문에서 뗀다")
class FrontmatterTest {
  @Test
  @DisplayName("flow 목록 tags는 쉼표로 나누고 따옴표를 벗기며 빈 항목은 버린다")
  void flowTags() {
    // when
    Frontmatter fm = Frontmatter.parse("---\ntags: [a, \"b c\", , 'd']\n---\n본문");

    // then
    assertThat(fm.tags()).containsExactly("a", "b c", "d");
    assertThat(fm.content()).isEqualTo("본문");
  }

  @Test
  @DisplayName("블록 목록 tags는 `- `로 시작하는 줄이 이어지는 동안 읽는다")
  void blockTags() {
    // when
    Frontmatter fm =
        Frontmatter.parse("---\ntags:\n  - one\n  - \"two\"\ntitle: x\n  - three\n---\n");

    // then
    assertThat(fm.tags()).containsExactly("one", "two");
  }

  @Test
  @DisplayName("tags 뒤에 목록이 아닌 값이 오면 태그가 없다")
  void scalarTagsIgnored() {
    // when
    Frontmatter fm = Frontmatter.parse("---\ntags: one\n---\n");

    // then
    assertThat(fm.tags()).isEmpty();
  }

  @Test
  @DisplayName("tags 줄이 여럿이면 차례로 이어 붙이고, partition은 마지막 값이다")
  void repeatedKeys() {
    // when
    Frontmatter fm =
        Frontmatter.parse("---\ntags: [a]\npartition: '/x'\ntags:\n- b\npartition: /rules\n---\n");

    // then
    assertThat(fm.tags()).containsExactly("a", "b");
    assertThat(fm.partition()).contains("/rules");
  }

  @Test
  @DisplayName("BOM은 떼고, 머리말이 없으면 본문 그대로다")
  void noFrontmatter() {
    // when
    Frontmatter fm = Frontmatter.parse("﻿# 제목\n본문");

    // then
    assertThat(fm).isEqualTo(new Frontmatter(List.of(), Optional.empty(), "# 제목\n본문"));
  }
}

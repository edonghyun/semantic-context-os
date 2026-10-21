#!/usr/bin/env python3
"""test-census.py 규칙 자체의 테스트(TESTING §13.1, F15 T15.3): 위반·통과 표본으로 연결형 제목(C3) 판정을 고정한다.

실행: python3 -m unittest tools/test_census_test.py  (저장소 루트에서)
"""
import importlib.util
import os
import tempfile
import unittest

_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test-census.py")
_spec = importlib.util.spec_from_file_location("test_census", _PATH)
census = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(census)


class SplitSignalTest(unittest.TestCase):
    """C3: "~하면 ~이고, ~한다"(효과 둘)는 허용, 서술 셋 이상이나 "그리고" 둘 이상은 쪼갬 신호."""

    def test_condition_and_two_effects_pass(self):
        self.assertFalse(census.splits("같은 결제를 다시 보내면 이미 기록된 것으로 인정되고, 돈은 한 번만 움직인다"))

    def test_two_effects_without_condition_pass(self):
        self.assertFalse(census.splits("새 DB를 지우고 restore.failed를 남긴다"))

    def test_three_predicates_split(self):
        self.assertTrue(census.splits("원본을 읽고 유닛을 쓰며 요청을 지운다"))

    def test_two_geurigo_split(self):
        self.assertTrue(census.splits("원본을 읽는다 그리고 쓴다 그리고 지운다"))

    def test_nouns_and_quotes_are_not_connectives(self):
        self.assertFalse(census.splits("최고 점수를 보고 결과라고 적는다"))


class ListTitleTest(unittest.TestCase):
    """다계약 휴리스틱의 나열형 제목: "그리고" 하나는 C3 안이고 둘이면 나열이다."""

    def test_single_geurigo_is_not_list(self):
        self.assertIsNone(census.LIST_TITLE.search("읽는다 그리고 쓴다"))

    def test_double_geurigo_is_list(self):
        self.assertIsNotNone(census.LIST_TITLE.search("읽는다 그리고 쓴다 그리고 지운다"))


class SpecShapeTest(unittest.TestCase):
    """스펙 모양 수치(C2·C4·C5)의 판정."""

    def test_class_display_name_found(self):
        src = '/** 설명 */\n@DisplayName("규칙 — 근거")\nclass FooTest {\n}\n'
        self.assertTrue(census.class_display_name(src))

    def test_class_display_name_missing(self):
        src = '/** 설명 */\n@Tag("db")\nclass FooTest {\n}\n'
        self.assertFalse(census.class_display_name(src))

    def test_gwt_comment_counts_in_body(self):
        lines = ["  void t() {", "    // when", "    run();", "  }"]
        self.assertTrue(census.GWT_COMMENT.search("\n".join(census.body_span(lines, 0))))

    def test_legacy_precondition_forms(self):
        src = 'assertThat(x).as("전제: 있다"); throw new IllegalStateException("전제: 없다"); "given 준비 실패: 없다"'
        self.assertEqual(len(census.LEGACY_PRECONDITION.findall(src)), 2)


class BodySpanTest(unittest.TestCase):
    """본문 경계: 문자열·문자 리터럴·주석 안의 중괄호로 다음 테스트까지 넘어가지 않는다."""

    def test_unbalanced_brace_in_string_stays_in_body(self):
        lines = ["  void a() {", '    run("{", \'{\'); // {', "  }", "  void b() {", "    // then", "  }"]
        self.assertEqual(census.body_span(lines, 0), ['    run("{", \'{\'); // {'])


class TextBlockTest(unittest.TestCase):
    """텍스트 블록 안의 표본 코드는 테스트로 세지 않는다."""

    def test_sample_code_in_text_block_is_not_a_test(self):
        src = 'class RulesTest {\n  String s = """\n    @Test\n    void sample() {\n    }\n    """;\n}\n'
        self.assertEqual(census.tests(src), [])


class ExclusionWordTest(unittest.TestCase):
    """제외어 하나만 있는 제목: 그 제외어가 이음으로 세어지면 이음이 둘이 되어 쪼갬 신호가 된다."""

    def test_noun_bogo_is_not_connective(self):
        self.assertFalse(census.splits("보고 형식을 읽고, 쓴다"))

    def test_quote_rago_is_not_connective(self):
        self.assertFalse(census.splits("완료라고 적고, 끝낸다"))


SAMPLE = """package io.scos.sample;

@DisplayName("규칙 — 근거")
class SampleTest {
  @Nested
  @DisplayName("상황")
  class Inner {
    @Test
    @DisplayName("원본을 읽고 유닛을 쓰며 요청을 지운다")
    void threeEffects() {
      // when
      run();
    }
  }

  @Test
  @DisplayName("값을 읽으면 기록되고, 돌려준다")
  void noComments() {
    if (x == null) throw new IllegalStateException("given 준비 실패: 없다");
    assertThat(run()).isTrue();
  }
}
"""

NO_CLASS_TITLE = """package io.scos.sample;

// 옛 제목 @DisplayName("이 줄은 주석이다")

/** 클래스 제목 없이 안쪽 상황에만 @DisplayName이 있다. */
class BareTest {
  @Nested
  @DisplayName("상황")
  class Inner {
    @Test
    @DisplayName("값을 돌려준다")
    void t() {
      // then
      assertThat(run()).isTrue();
    }
  }
}
"""


class WiringTest(unittest.TestCase):
    """tests()·census()가 판정 부품을 실제로 각 테스트·파일 합계에 이어 쓰는지(C3 이음·C2 주석·C4·C5)."""

    def test_tests_flags_connective_title(self):
        flags = {t["name"]: t["connective"] for t in census.tests(SAMPLE)}
        self.assertEqual(flags, {"threeEffects": True, "noComments": False})

    def test_tests_reads_gwt_comment_from_raw_body(self):
        flags = {t["name"]: t["gwt"] for t in census.tests(SAMPLE)}
        self.assertEqual(flags, {"threeEffects": True, "noComments": False})

    def test_census_totals_spec_shape(self):
        with tempfile.TemporaryDirectory() as root:
            for name, src in (("SampleTest.java", SAMPLE), ("BareTest.java", NO_CLASS_TITLE)):
                path = os.path.join(root, "backend/scos-sample/src/test/java/io/scos/sample", name)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(src)
            rows, connective_titles, _ = census.census(root)
        by_file = {r["file"].split("/")[-1]: (r["class_dn"], r["no_gwt"], r["given_fail"]) for r in rows}
        self.assertEqual(by_file, {"SampleTest.java": (True, 1, 1), "BareTest.java": (False, 0, 0)})
        self.assertEqual([t for _, _, t in connective_titles], ["원본을 읽고 유닛을 쓰며 요청을 지운다"])


    def test_census_reads_test_and_test_fixtures_of_every_module(self):
        with tempfile.TemporaryDirectory() as root:
            for d in ("backend/scos-a/src/test/java", "backend/scos-b/src/testFixtures/java", "harness/src/test/java"):
                path = os.path.join(root, d, "io/scos/sample/SampleTest.java")
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(SAMPLE)
            rows, _, _ = census.census(root)
        self.assertEqual(
            sorted(r["file"] for r in rows),
            ["backend/scos-a/sample/SampleTest.java", "backend/scos-b/sample/SampleTest.java", "harness/sample/SampleTest.java"],
        )


if __name__ == "__main__":
    unittest.main()

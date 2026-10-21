#!/usr/bin/env python3
"""테스트 가독성 census — backend·harness Java 테스트의 정량 지표(TESTING §2.5·§13.1).

사용:
  python3 tools/test-census.py [저장소 루트] [--out 표.md] [--list-connective] [--list-multi-subject]
  규칙 자체: python3 -m unittest tools/test_census_test.py

이 스크립트는 **휴리스틱**이다.
"다계약 0"은 아래 정규식 기준의 0이지 리뷰 판정이 아니다 — 연결형 제목 목록(--list-connective)은
리뷰가 하나씩 판정한다(TESTING §2.4 "계약 하나의 판정").

지표(테스트 메서드 = @Test·@ParameterizedTest):
  본문 크기   문장 수(세미콜론 + 블록을 여는 if/for/while/try/switch). 포매터가 빌더 체인을 여러 줄로 펴도 1로 센다.
  단언 수     assertThat…(·assertSoftly( 호출 수. 도우미 메서드 안의 단언은 세지 않는다.
  다계약      단언 > 3, 또는 나열형 제목(→·"그리고" 둘·쉼표 둘·"…하고 …하며"), 또는 61자 이상 제목.
  연결형 제목 제목의 이음(연결어미 -고/-며/-지만/-되/-면서, "그리고")이 둘 이상이다 — 서술 셋 이상(C3: "~하면 ~이고, ~한다"처럼
              효과 둘은 허용). @Nested 상황 제목은 세지 않는다. 규칙 자체는 tools/test_census_test.py.
  FQCN        본문의 완전 수식 타입 이름.
  Duration·매직  Duration.of…/parse 호출 + 두 자리 이상 숫자 리터럴(단언 줄·단일 리터럴 인자 호출 `.of(5)` 제외).
  준비 노출   래치·스레드·실행기, 단언 밖 블록 람다·익명 클래스, 지표 레지스트리 탐색.
  적합        본문 최장 ≤ 12, 단언 ≤ 3, 다계약 0, FQCN 0, 노출 0, 리터럴 ≤ 테스트 수.
"""
import argparse
import os
import re

DIRS = ["backend/src/test/java", "harness/src/test/java"]
# 규칙 표본 패키지(위반 표본을 일부러 담는다) — 규칙 테스트처럼 대상 밖이다.
RULE_SAMPLE_PACKAGES = {"scosrules", "harnessrules"}
FQ = re.compile(r'(?<![\w."/])(?:io\.scos|java|javax|org|com|tools)(?:\.[a-z]\w*)+\.[A-Z]\w*')
SYNC = re.compile(
    r"\b(CountDownLatch|CyclicBarrier|new Thread\b|Executors\.|CompletableFuture\.|\.await\(\d|\.join\(\d|Thread\.State)"
)
DUR = re.compile(r"Duration\.(of\w*|parse)\(")
MAGIC = re.compile(r'(?<![\w."\'$])(?:\d{2,}|\d+_\d+)(?:L)?(?![\w."])')
LAMBDA_BLOCK = re.compile(r"(new \w+(<[^>]*>)?\(\)\s*\{|->\s*\{)")
REGISTRY = re.compile(r"\.find\(|registry\.get\(")
LIST_TITLE = re.compile(r"→|그리고.*그리고|,[^,]*,|하고 [^,]*하며")
ASSERTION = re.compile(r"\bassertThat(?:Thrown(?:By)?|Code|Metrics)?\(|assertSoftly\(")
ASSERTION_LINE = re.compile(r"assertThat|\.is\w*\(|\.has\w*\(|\.contains\w*\(|\.extracting")
LAMBDA_IN_ASSERTION = re.compile(
    r"assertThat|\.satisf|\.allSatisf|\.anySatisf|\.isInstanceOfSatisfying|\.hasValueSatisfying"
)

# 스펙 모양(C2·C4·C5) — 적합 판정에는 넣지 않고 전후 수치로만 본다(T15.4 정리 대상).
GWT_COMMENT = re.compile(r"//\s*(given|when|then)\b")
LEGACY_PRECONDITION = re.compile(r'"전제[:]|as\("전제')
GIVEN_FAILURE = re.compile(r'"given 준비 실패')
TOP_CLASS = re.compile(r"^(?:public\s+)?(?:abstract\s+|final\s+)*class\s+\w+", re.M)


def class_display_name(src):
    """최상위 테스트 클래스 바로 위에 @DisplayName이 있는가(C5)."""
    m = TOP_CLASS.search(src)
    if not m:
        return True
    lines = src[: m.start()].rstrip("\n").split("\n")
    return "@DisplayName" in annotations_above(lines + [""], len(lines))


# 연결어미로 끝나는 어절. 뒤에 공백이나 쉼표가 와야 한다(어절 끝).
CONNECTIVE = re.compile(r"[가-힣]+(?:고|며|지만|되|면서)(?=[ ,])")
# 연결어미가 아닌 같은 꼴: 명사(최고·참고·보고), 인용(-라고·-다고), 진행형(-고 있다), 조사 "만"(페이지만·까지만).
NOT_CONNECTIVE_WORDS = {"최고", "참고", "보고"}
NOT_CONNECTIVE_ENDINGS = ("라고", "다고", "페이지만", "까지만")


def connectives(title):
    """제목에서 두 서술을 잇는 연결어미 어절 목록."""
    found = []
    for m in CONNECTIVE.finditer(title):
        word = m.group(0)
        if word in NOT_CONNECTIVE_WORDS or word.endswith(NOT_CONNECTIVE_ENDINGS):
            continue
        if title[m.end() :].startswith(" 있"):  # 들고 있는, 남아 있고 …
            continue
        found.append(word)
    return found


def strip_strings(s):
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', s)


def strip_chars(s):
    """문자 리터럴('{'·'\\'')을 비운다 — 본문 경계를 셀 때 중괄호로 보지 않게."""
    return re.sub(r"'(?:\\.|[^'\\])'", "''", s)


CLASS_OPEN = re.compile(r"\b(class|interface|enum|record)\b.*\{$")
METHOD_OPEN = re.compile(r"\)\s*(throws\s[\w.,\s]+)?\{$")


def annotations_above(lines, i):
    """메서드 선언 위의 어노테이션 블록. 앞 멤버의 끝(빈 줄·`}`·`;`)이나 주석에서 멈춘다.

    여러 줄 배열 인자(`@CsvSource({` … `})`)도 블록에 포함한다 — 2026-10-15 이전 판은 `({`에서 멈춰
    그런 파라미터화 테스트를 세지 못했다.
    """
    k = i - 1
    anns = []
    while k >= 0:
        s = lines[k].strip()
        if s == "" or s == "}" or s.endswith(";") or s.startswith(("//", "/*", "*")):
            break
        if not s.startswith("@") and (CLASS_OPEN.search(s) or METHOD_OPEN.search(s)):
            break
        anns.append(lines[k])
        k -= 1
    return " ".join(reversed(anns))


ENTRY = re.compile(r"\b(assertThat|assertThatThrownBy|assertThatCode|assertThatMetrics|assertSoftly)\(")
PREMISE = re.compile(r'\.as\(\s*"전제')
LOCAL_DECL = re.compile(r"(?:^|[\s(,])(?:final\s+)?[A-Z][\w.<>\[\], ?]*\s+([a-z]\w*)\s*(?:=|:|\))|\bvar\s+([a-z]\w*)\s*=")


def _call_arg(text, start):
    """text[start]가 '('일 때 짝 괄호 안의 문자열."""
    depth = 0
    for k in range(start, len(text)):
        if text[k] == "(":
            depth += 1
        elif text[k] == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1 : k]
    return text[start + 1 :]


def _subject(arg, locals_):
    """단언 대상의 주체: 지역 값이면 그 이름, 필드·시나리오면 이름.첫 관찰, 함수 호출이면 첫 인자의 주체."""
    arg = arg.strip()
    m = re.match(r"([A-Za-z_]\w*)\s*(\(|\.\s*([A-Za-z_]\w*))?", arg)
    if not m:
        return arg[:30]
    root = m.group(1)
    if m.group(2) == "(":  # ids(got) 같은 보기 함수 → 인자의 주체
        inner = _call_arg(arg, m.end(2) - 1)
        return _subject(inner, locals_) if inner.strip() else root
    if root in locals_ or not m.group(3):
        return root
    return root + "." + m.group(3)


def subjects(raw_body, params=""):
    """비전제 단언들의 주체 집합(다주체 단언 리뷰 목록용). 휴리스틱이다."""
    text = "\n".join(raw_body)
    locals_ = {a or b for a, b in LOCAL_DECL.findall(params + "\n" + text)}
    found = []
    covered = -1  # 앞 단언문의 끝. 그 안(람다 속 단언)은 바깥 단언의 일부다
    for m in ENTRY.finditer(text):
        if m.start() < covered:
            continue
        end = text.find(";", m.end())
        covered = end if end >= 0 else len(text)
        stmt = text[m.start() : covered]
        if PREMISE.search(stmt):
            continue
        kind = m.group(1)
        if kind == "assertThat":
            found.append(_subject(_call_arg(text, m.end() - 1), locals_))
        else:
            found.append({"assertThatThrownBy": "<thrown>", "assertThatCode": "<code>"}.get(kind, "<" + kind + ">"))
    return found


def body_span(lines, i):
    """메서드 본문 줄(여는·닫는 중괄호 줄 제외, 주석 포함)."""
    depth, start, j = 0, None, i
    while j < len(lines):
        code = strip_chars(strip_strings(lines[j])).split("//")[0]  # 문자열·문자·줄 주석 안의 중괄호는 세지 않는다
        depth += code.count("{") - code.count("}")
        if start is None and "{" in code:
            start = j
        if start is not None and depth == 0:
            break
        j += 1
    return lines[start + 1 : j]


def body_of(lines, i, raw=False):
    kept = [b for b in body_span(lines, i) if b.strip() and not b.strip().startswith("//")]
    return kept if raw else [strip_strings(b) for b in kept]


def splits(title):
    """쪼갬 신호(TESTING §2.2 C3): "~하면 ~이고, ~한다"처럼 효과 둘(이음 하나)은 허용한다. 이음(연결어미·"그리고")이 둘 이상이면
    서술이 셋 이상이라 쪼갤 신호다."""
    return len(connectives(title)) >= 2


def without_text_blocks(src):
    """텍스트 블록 내용을 빈 줄로 바꾼다(줄 번호 유지) — 규칙 자체 테스트가 담은 표본 코드를 테스트로 세지 않게."""
    return re.sub(r'"""[\s\S]*?"""', lambda m: '""' + "\n" * m.group(0).count("\n"), src)


def tests(src):
    lines = without_text_blocks(src).split("\n")
    out = []
    for i, line in enumerate(lines):
        m = re.search(r"^\s*(?:public\s+|protected\s+)?void (\w+)\(", line)
        if not m:
            continue
        anns = annotations_above(lines, i)
        if "@Test" not in anns and "@ParameterizedTest" not in anns:
            continue
        mm = re.search(r'@DisplayName\(\s*"((?:[^"\\]|\\.)*)"', anns)
        title = mm.group(1) if mm else ""
        body = body_of(lines, i)
        joined = "\n".join(body)
        statements = joined.count(";") + len(re.findall(r"\b(if|for|while|try|switch)\b[^;{]*\{", joined))
        out.append(
            dict(
                name=m.group(1),
                line=i + 1,
                title=title,
                statements=statements,
                asserts=sum(len(ASSERTION.findall(b)) for b in body),
                fq=sum(len(FQ.findall(b)) for b in body),
                sync=sum(len(SYNC.findall(b)) for b in body),
                dur=sum(len(DUR.findall(b)) for b in body),
                magic=sum(
                    len(MAGIC.findall(re.sub(r"\.\w+\(\s*-?[\d_.]+L?\s*\)", "", b)))
                    for b in body
                    if not ASSERTION_LINE.search(b)
                ),
                lam=sum(len(LAMBDA_BLOCK.findall(b)) for b in body if not LAMBDA_IN_ASSERTION.search(b)),
                reg=sum(len(REGISTRY.findall(b)) for b in body),
                listy=bool(LIST_TITLE.search(title)) or len(title) > 60,
                connective=splits(title),
                subjects=subjects(body_of(lines, i, raw=True), line),
                gwt=bool(GWT_COMMENT.search("\n".join(body_span(lines, i)))),
            )
        )
    return out


def census(root):
    rows, connective_titles, multi_subject = [], [], []
    for d in DIRS:
        for dp, _, fs in os.walk(os.path.join(root, d)):
            if os.path.basename(dp) in RULE_SAMPLE_PACKAGES:
                continue
            for f in fs:
                if not f.endswith(".java"):
                    continue
                p = os.path.join(dp, f)
                src = open(p, encoding="utf-8").read()
                t = tests(src)
                if not t:
                    continue
                rel = os.path.relpath(p, root).replace("src/test/java/io/scos/", "")
                for x in t:
                    if x["connective"]:
                        connective_titles.append((rel, x["line"], x["title"]))
                    if len(set(x["subjects"])) >= 2:
                        multi_subject.append((rel, x["line"], x["title"] + "  ⟨" + ", ".join(dict.fromkeys(x["subjects"])) + "⟩"))
                multi = sum(1 for x in t if x["asserts"] > 3 or x["listy"])
                conn = sum(1 for x in t if x["connective"])
                subj = sum(1 for x in t if len(set(x["subjects"])) >= 2)
                exposed = sum(x["sync"] + x["lam"] + x["reg"] for x in t)
                lits = sum(x["dur"] + x["magic"] for x in t)
                fq = sum(x["fq"] for x in t)
                mx = max(x["statements"] for x in t)
                ma = max(x["asserts"] for x in t)
                score = max(0, mx - 12) + 3 * max(0, ma - 3) + 4 * multi + 3 * fq + 2 * exposed + max(0, lits - len(t))
                rows.append(
                    dict(
                        class_dn=class_display_name(src),
                        legacy_pre=len(LEGACY_PRECONDITION.findall(src)),
                        given_fail=len(GIVEN_FAILURE.findall(src)),
                        no_gwt=sum(1 for x in t if not x["gwt"]),
                        file=rel,
                        tests=len(t),
                        avg=sum(x["statements"] for x in t) / len(t),
                        max=mx,
                        amax=ma,
                        multi=multi,
                        conn=conn,
                        subj=subj,
                        fq=fq,
                        lits=lits,
                        exposed=exposed,
                        score=score,
                        fit=score == 0,
                        size="S" if score < 25 else ("M" if score < 120 else "L"),
                        db='@Tag("db")' in src or '@Tag({"db"' in src,
                    )
                )
    rows.sort(key=lambda r: (r["fit"], -r["score"], r["file"]))
    connective_titles.sort()
    multi_subject.sort()
    return rows, connective_titles, multi_subject


def summary(rows):
    return (
        f"파일 {len(rows)}, 테스트 메서드 {sum(r['tests'] for r in rows)}, 적합 {sum(r['fit'] for r in rows)}, "
        f"다계약(휴리스틱) {sum(r['multi'] for r in rows)}, 연결형 제목 {sum(r['conn'] for r in rows)}, "
        f"다주체 단언 {sum(r['subj'] for r in rows)}, "
        f"FQCN {sum(r['fq'] for r in rows)}, Duration·매직 {sum(r['lits'] for r in rows)}, "
        f"준비 노출 {sum(r['exposed'] for r in rows)}, 본문 최장 {max(r['max'] for r in rows)}, "
        f"최대 단언 {max(r['amax'] for r in rows)}\n"
        f"스펙 모양(T15.4 대상): 클래스 DisplayName 없는 파일 {sum(not r['class_dn'] for r in rows)}, "
        f"given/when/then 주석 없는 테스트 {sum(r['no_gwt'] for r in rows)}, "
        f"옛 전제 모양(\"전제:\"·as(\"전제…\")) {sum(r['legacy_pre'] for r in rows)}, "
        f"\"given 준비 실패\" {sum(r['given_fail'] for r in rows)}"
    )


def table(rows):
    out = [
        "| # | 파일 | 테스트 | 본문 평균/최장 | 최대 단언 | 다계약 | 연결형 | 다주체 | FQCN | Duration·매직 | 준비 노출 | DB | 점수 | 판정·크기 |",
        "|---:|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---|---:|---|",
    ]
    for i, r in enumerate(rows, 1):
        out.append(
            f"| {i} | `{r['file']}` | {r['tests']} | {r['avg']:.1f}/{r['max']} | {r['amax']} | {r['multi']} | {r['conn']} | {r['subj']} | "
            f"{r['fq']} | {r['lits']} | {r['exposed']} | {'db' if r['db'] else ''} | {r['score']} | "
            f"{'적합' if r['fit'] else '대상 ' + r['size']} |"
        )
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("root", nargs="?", default=".")
    ap.add_argument("--out", help="파일별 표를 쓸 Markdown 경로")
    ap.add_argument("--list-connective", action="store_true", help="연결형 제목을 리뷰 목록으로 출력")
    ap.add_argument("--list-multi-subject", action="store_true", help="다주체 단언 테스트를 리뷰 목록으로 출력")
    args = ap.parse_args()
    rows, connective_titles, multi_subject = census(args.root)
    print(summary(rows))
    if args.list_connective:
        for rel, line, title in connective_titles:
            print(f"  {rel}:{line}  {title}")
    if args.list_multi_subject:
        for rel, line, title in multi_subject:
            print(f"  {rel}:{line}  {title}")
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(summary(rows) + "\n\n" + table(rows) + "\n")


if __name__ == "__main__":
    main()

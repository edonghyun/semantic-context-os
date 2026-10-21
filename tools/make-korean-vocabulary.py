#!/usr/bin/env python3
"""규모 프로브(SearchScaleProbeTest)의 합성 한국어 어휘 목록을 만든다(F3 리뷰 I4, 공개 감사 D3).

입력 텍스트가 없다. 한글 자모(초성·중성·종성)에 손으로 정한 가중치를 곱해 음절 목록을 고르고, 음절을 이어 어간을 만든 뒤 흔한 조사·어미
(일반 문법 형태)를 붙여 단어를 만든다. 등장 횟수는 Zipf 꼴 공식으로 정한다. 그래서 실존 고유명사·문서 내용을 담지 않는다 — 우연히 실제
단어와 같은 모양이 나올 수는 있다. 맞추는 것은 규모 프로브가 기대는 통계뿐이다: 음절 약 900종, 단어 안 bigram 약 1만 종, 어휘 약 1만
6천, 토큰 약 12만, 토큰 길이 분포(2음절이 절반 남짓), 한 번만 나오는 단어 절반가량. 음절 50개 합성 어휘는 bigram이 약 2,500종뿐이라
포스팅 목록이 몇 배 길었다(I4) — 그래서 음절·bigram 종류 수를 실제 한국어 기술 문서 수준으로 맞춘다. 아래 상수는 이 통계에 맞춰 손으로
조정한 값이다(어떤 입력 데이터에서 뽑은 값도 아니다).

난수는 random.Random(SEED).random()만 쓴다(파이썬 버전이 바뀌어도 같은 수열). 같은 상수면 같은 파일이다.
출력: 첫 줄 주석(생성기·통계), 이어서 "단어<TAB>횟수"를 횟수 내림차순(같으면 단어 순).

usage: python3 tools/make-korean-vocabulary.py > backend/scos-engine/src/test/resources/perf/korean-vocabulary.tsv
"""
import math, random, sys

SEED = 20261019
SYLLABLES = 1_060
TYPES = 16_300
TOKENS = 122_500
STEMS = 4_500
SYLLABLE_OFFSET = 8
SYLLABLE_EXPONENT = 1.4
STEM_EXPONENT = 0.6
COUNT_OFFSET = 31
COUNT_EXPONENT = 1.18
# 단어 길이(음절, 마지막 칸은 그 이상)별 점수 기본값과 잡음 크기
LENGTH_BIAS = [0.2, 0.2, 0.1, -0.05, -1.2, -2.0]
NOISE = 0.8

# 초성 19·중성 21·종성 28(첫 칸은 받침 없음)의 대략적 상대 빈도(손으로 정한 값)
INITIAL = [9, 2, 6, 6, 1, 4, 5, 4, 1, 7, 2, 14, 7, 1, 3, 1, 2, 2, 6]
MEDIAL = [14, 4, 1, 0.2, 7, 3, 4, 0.5, 8, 2, 0.3, 1, 1, 7, 1, 0.1, 0.5, 1, 6, 1, 12]
FINAL = [40, 4, 0.3, 0.1, 10, 0.1, 0.1, 1, 8, 0.2, 0.3, 0.1, 0.1, 0.1, 0.1, 0.1,
         5, 2, 0.1, 1, 0.3, 9, 0.5, 0.3, 0.1, 0.3, 0.3, 0.3]
# 체언 뒤 조사·용언 어미 같은 흔한 문법 형태(어휘가 아니라 문법이다)
SUFFIXES = ["는", "은", "를", "을", "이", "가", "에", "의", "로", "으로", "와", "과", "도", "만", "에서",
            "까지", "부터", "보다", "처럼", "한다", "하는", "하고", "해서", "하면", "했다", "된다", "되는",
            "된", "한", "할", "이다", "인", "있다", "없다", "같은", "에게", "라는", "이라", "마다", "들"]
# 어간 길이(음절)와 상대 빈도, 조사·어미를 붙일 확률
STEM_LENGTHS = [(1, 5), (2, 14), (3, 8.45), (4, 2), (5, 0.455), (6, 0.15)]
SUFFIX_SHARE = 0.6
SHORT_SUFFIX_WEIGHT = 3


def weighted(rng, items, weights):
    x = rng.random() * sum(weights)
    for item, w in zip(items, weights):
        x -= w
        if x < 0:
            return item
    return items[-1]


def syllable(i, m, f):
    return chr(0xAC00 + (i * 21 + m) * 28 + f)


def syllable_inventory(rng):
    """자모 가중치로 서로 다른 음절을 뽑는다. 뽑힌 순서가 곧 빈도 순위다(흔한 자모 조합이 먼저 나온다)."""
    picked, seen = [], set()
    while len(picked) < SYLLABLES:
        s = syllable(weighted(rng, range(19), INITIAL), weighted(rng, range(21), MEDIAL),
                     weighted(rng, range(28), FINAL))
        if s not in seen:
            seen.add(s)
            picked.append(s)
    return picked


def build():
    """(단어, 횟수) 목록을 횟수 내림차순(같으면 단어 순)으로 만든다."""
    rng = random.Random(SEED)
    inventory = syllable_inventory(rng)
    syllable_weights = [1 / (r + SYLLABLE_OFFSET) ** SYLLABLE_EXPONENT for r in range(len(inventory))]
    lengths, length_weights = zip(*STEM_LENGTHS)
    stems, seen = [], set()
    while len(stems) < STEMS:
        stem = "".join(weighted(rng, inventory, syllable_weights)
                       for _ in range(weighted(rng, lengths, length_weights)))
        if stem not in seen:
            seen.add(stem)
            stems.append(stem)
    # 흔한 어간일수록 여러 조사·어미와 함께 나온다(어간 순위도 Zipf)
    stem_weights = [1 / (r + 10) ** STEM_EXPONENT for r in range(len(stems))]
    # 한 음절 조사가 더 흔하다
    suffix_weights = [SHORT_SUFFIX_WEIGHT if len(x) == 1 else 1 for x in SUFFIXES]
    scored, seen = [], set()
    while len(scored) < TYPES:
        stem = weighted(rng, stems, stem_weights)
        word = stem + (weighted(rng, SUFFIXES, suffix_weights) if rng.random() < SUFFIX_SHARE else "")
        if word in seen:
            continue
        seen.add(word)
        # 점수가 높을수록 앞 순위로 가서 많이 나온다: 길이별 기본값 + 잡음
        score = LENGTH_BIAS[min(len(word), len(LENGTH_BIAS)) - 1] + NOISE * math.log(rng.random() + 1e-12)
        scored.append((score, word))
    scored.sort(key=lambda e: (-e[0], e[1]))
    raw = [1 / (r + COUNT_OFFSET) ** COUNT_EXPONENT for r in range(TYPES)]
    scale = TOKENS / sum(raw)
    counts = [max(1, round(x * scale)) for x in raw]
    return sorted(zip((w for _, w in scored), counts), key=lambda e: (-e[1], e[0]))


def main():
    rows = build()
    used = {ch for w, _ in rows for ch in w}
    bigrams = {w[i:i + 2] for w, _ in rows for i in range(len(w) - 1)}
    print(f"# 합성(tools/make-korean-vocabulary.py, 시드 {SEED}), 어휘 {len(rows)}, "
          f"토큰 {sum(n for _, n in rows)}, 음절 {len(used)}, bigram {len(bigrams)}")
    for word, n in rows:
        print(f"{word}\t{n}")


if __name__ == "__main__":
    sys.exit(main())

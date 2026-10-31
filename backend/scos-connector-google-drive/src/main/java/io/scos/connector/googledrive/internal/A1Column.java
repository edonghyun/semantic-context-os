package io.scos.connector.googledrive.internal;

/**
 * 0부터 센 열 번호의 A1 이름(A … Z, AA …). 번호는 응답의 JS 수라 double이다 — 1 미만(NaN 포함)이면 빈 이름. 범위 키를 만들 때(정수)와 셀을
 * 렌더링할 때(응답의 시작 열) 같은 이름을 쓴다.
 */
record A1Column(double index) {
  private static final long ALPHABET = 26;

  String name() {
    double number = index + 1;
    return number > 0 ? name(number) : "";
  }

  /** 1부터 센 열 번호의 이름: 앞자리 이름 + 끝 글자. */
  private static String name(double number) {
    char last = (char) ('A' + (long) ((number - 1) % ALPHABET));
    double rest = Math.floor((number - 1) / ALPHABET);
    return rest > 0 ? name(rest) + last : String.valueOf(last);
  }
}

package io.scos.engine.support.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * WHATWG UTF-8 디코드: 기대값은 Node `new TextDecoder().decode(Buffer.from(hex, "hex"))`의 코드 포인트(16진)다.
 * 잘못된 시퀀스마다 대체 문자 수가 TS와 같아야 원본 본문 해시가 같다.
 */
@DisplayName("UTF-8 디코드는 잘못된 시퀀스까지 TS TextDecoder와 같다(WHATWG)")
class Utf8Test {
  static String codePoints(String s) {
    return s.codePoints().mapToObj(c -> String.format("%04x", c)).collect(Collectors.joining(" "));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "UTF-8로 쓴 서로게이트|eda080|fffd fffd fffd",
        "CESU-8 이모지|eda0bdedb880|fffd fffd fffd fffd fffd fffd",
        "끝에서 끊긴 3바이트|e282|fffd",
        "과잉 길이 4바이트|f0808080|fffd fffd fffd fffd",
        "과잉 길이 2바이트|c0af|fffd fffd",
        "U+10FFFF 초과|f4908080|fffd fffd fffd fffd",
        "FF 단독|ff|fffd",
        "끝에서 끊긴 4바이트|f09f98|fffd",
        "정상 혼합|41e2828cf09f9880|0041 208c 1f600",
        "과잉 길이 3바이트|e08080|fffd fffd fffd",
        "가운데 끊긴 3바이트|e2824142|fffd 0041 0042",
        "서로게이트 바로 앞|ed9fbf|d7ff",
        "U+FFFF|efbfbf|ffff",
        "U+10FFFF|f48fbfbf|10ffff",
        "연속 바이트 단독|80|fffd",
        "앞 BOM은 뗀다|efbbbf41|0041",
        "BOM 앞 두 바이트만|efbb|fffd",
        "BOM만|efbbbf|''",
        "ASCII 끝|7f|007f",
        "2바이트 최소|c280|0080",
        "2바이트 과잉 길이 C1|c180|fffd fffd",
        "2바이트 최대|dfbf|07ff",
        "3바이트 최소|e0a080|0800",
        "E0 뒤 9F|e09f80|fffd fffd fffd",
        "4바이트 최소|f0908080|10000",
        "F0 뒤 8F|f08f8080|fffd fffd fffd fffd",
        "F5 리드|f580|fffd fffd",
        "연속 자리의 ASCII|c27f|fffd 007f",
        "연속 자리의 C0|c2c0|fffd fffd",
        "E1 3바이트|e18080|1000",
        "EE 3바이트|ee8080|e000"
      })
  @DisplayName("TS TextDecoder와 같은 문자열로 읽는다")
  void decodesLikeTextDecoder(String label, String hex, String expected) {
    // when
    String decoded = Utf8.decodeLikeTextDecoder(HexFormat.of().parseHex(hex));

    // then
    assertThat(codePoints(decoded)).isEqualTo(expected);
  }
}

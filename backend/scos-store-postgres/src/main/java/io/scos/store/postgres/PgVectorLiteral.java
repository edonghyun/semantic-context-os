package io.scos.store.postgres;

import java.util.regex.Pattern;

/**
 * pgvector 텍스트 표기 "[a,b,c]" — 벡터 색인과 임베딩 캐시가 같이 쓴다. 쓸 때는 double의 최단 왕복 표기라 값이 보존되고, 읽는 값은 pgvector가
 * 저장한 float4다(정밀도 주의).
 */
final class PgVectorLiteral {
  private static final Pattern COMMA = Pattern.compile(",");

  private PgVectorLiteral() {}

  static String format(double[] v) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < v.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(v[i]);
    }
    return sb.append(']').toString();
  }

  static double[] parse(String literal) {
    String body = literal.substring(1, literal.length() - 1).trim();
    if (body.isEmpty()) return new double[0];
    String[] parts = COMMA.split(body, -1);
    double[] v = new double[parts.length];
    for (int i = 0; i < parts.length; i++) v[i] = Double.parseDouble(parts[i].trim());
    return v;
  }
}

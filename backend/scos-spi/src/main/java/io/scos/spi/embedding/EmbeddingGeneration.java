package io.scos.spi.embedding;

import java.util.regex.Pattern;

/**
 * 임베딩 세대(stage2 §3.1): {@code <provider>:<model>:<dim>:<input-format>}. 같은 세대의 벡터끼리만 비교·재사용한다 — 공간의
 * {@code embedding:version}과 임베딩 캐시의 키 공간이다. 세대는 늘 {@link #value()} 문자열의 동등으로 비교하고, 옛 형식을 해석하는 별칭은
 * 없다(옛 라벨은 V18이 고쳐 썼다).
 *
 * <p>칸 제약: 공급자 {@code [a-z0-9][a-z0-9-]*}, 모델은 비어 있지 않고 공백이 없다(콜론·슬래시는 된다), 차원 1 이상, 입력 형식 {@code
 * [a-z+]+}. 공급자 {@value #PENDING_PROVIDER}는 쓸 수 없다 — 공간의 {@code pending:<목표>}(끝나지 않은 재임베딩, §3.3)가
 * 세대로 읽히지 않게.
 */
public record EmbeddingGeneration(String provider, String model, int dim, String inputFormat) {
  private static final Pattern PROVIDER = Pattern.compile("[a-z0-9][a-z0-9-]*");
  private static final Pattern NO_WHITESPACE = Pattern.compile("\\S+");
  private static final Pattern DIM = Pattern.compile("[1-9][0-9]{0,8}");
  private static final Pattern INPUT_FORMAT = Pattern.compile("[a-z+]+");

  /** 재임베딩 표시의 머리말 이름 — 세대의 공급자로는 거부한다. */
  public static final String PENDING_PROVIDER = "pending";

  public EmbeddingGeneration {
    if (provider == null || !PROVIDER.matcher(provider).matches())
      throw new IllegalArgumentException("generation provider must match [a-z0-9][a-z0-9-]*");
    if (provider.equals(PENDING_PROVIDER))
      throw new IllegalArgumentException(
          "generation provider '" + PENDING_PROVIDER + "' is reserved for unfinished re-embedding");
    if (model == null || !NO_WHITESPACE.matcher(model).matches())
      throw new IllegalArgumentException("generation model must be non-empty without whitespace");
    if (dim < 1) throw new IllegalArgumentException("generation dim must be >= 1: " + dim);
    if (inputFormat == null || !INPUT_FORMAT.matcher(inputFormat).matches())
      throw new IllegalArgumentException("generation input format must match [a-z+]+");
  }

  /** 공급자는 첫 ':' 앞, 입력 형식은 마지막 ':' 뒤, 차원은 그 앞 칸, 모델은 가운데 전부다(모델 이름에 ':'가 들어가도 된다). */
  public static EmbeddingGeneration parse(String value) {
    int first = value.indexOf(':');
    int last = value.lastIndexOf(':');
    int beforeLast = value.lastIndexOf(':', last - 1);
    if (beforeLast <= first)
      throw new IllegalArgumentException(
          "embedding generation must be <provider>:<model>:<dim>:<input-format>: " + value);
    String dim = value.substring(beforeLast + 1, last);
    if (!DIM.matcher(dim).matches())
      throw new IllegalArgumentException(
          "embedding generation dim must be a positive integer: " + value);
    return new EmbeddingGeneration(
        value.substring(0, first),
        value.substring(first + 1, beforeLast),
        Integer.parseInt(dim),
        value.substring(last + 1));
  }

  /** 임베더 identity와 엔진의 임베딩 입력 형식으로 만든 세대. */
  public static EmbeddingGeneration of(EmbedderIdentity identity, String inputFormat) {
    return new EmbeddingGeneration(
        identity.provider(), identity.model(), identity.dim(), inputFormat);
  }

  /** 정규 문자열 — {@link #parse}의 역. */
  public String value() {
    return provider + ":" + model + ":" + dim + ":" + inputFormat;
  }
}

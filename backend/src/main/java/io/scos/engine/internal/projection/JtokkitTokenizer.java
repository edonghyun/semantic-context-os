package io.scos.engine.internal.projection;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import io.scos.spi.projection.Tokenizer;

/**
 * o200k_base(JTokkit 1.1.0) — TS js-tiktoken o200k_base와 같은 랭크(ADR-0019). 인코딩은 한 번 만들고 재사용한다(스레드
 * 안전).
 */
public final class JtokkitTokenizer implements Tokenizer {
  private final Encoding encoding =
      Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

  @Override
  public int count(String text) {
    return encoding.countTokens(text);
  }
}

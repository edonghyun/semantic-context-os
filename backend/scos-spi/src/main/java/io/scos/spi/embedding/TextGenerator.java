package io.scos.spi.embedding;

/** 텍스트 생성 공급자 포트(외부 경계, 재정렬 LLM). 구현: Gemini HTTP, 하네스 카세트. 실패는 {@link GenerationException}. */
public interface TextGenerator {
  String generate(String prompt, int maxTokens);

  final class GenerationException extends RuntimeException {
    public GenerationException(String message) {
      super(message);
    }
  }
}

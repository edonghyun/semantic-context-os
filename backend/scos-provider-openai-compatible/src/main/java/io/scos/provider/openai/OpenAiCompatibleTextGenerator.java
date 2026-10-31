package io.scos.provider.openai;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderJsonHttp;
import io.scos.spi.embedding.TextGenerator;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 호환 /chat/completions 어댑터(공개 배포 단계 5 T2, 재정렬 LLM): {@code
 * {"model","messages":[{"role":"user", "content":prompt}],"max_tokens"}}를 보내고 {@code
 * choices[0].message.content}를 돌려준다. {@code max_completion_tokens}만 받는 모델은 0.1.0에서 지원하지 않는다. 실패
 * 메시지에 본문을 싣지 않는다(H6).
 */
public final class OpenAiCompatibleTextGenerator implements TextGenerator {
  private final ObjectMapper json;
  private final ProviderJsonHttp http;
  private final URI endpoint;
  private final String model;

  public OpenAiCompatibleTextGenerator(
      String baseUrl,
      Optional<String> apiKey,
      String model,
      ProviderCallExecutor executor,
      ObjectMapper json) {
    this.json = json;
    this.http = OpenAiCompatibleHttp.over(executor, apiKey);
    this.endpoint = OpenAiCompatibleHttp.endpoint(baseUrl, "/chat/completions");
    this.model = model;
  }

  @Override
  public String generate(String prompt, int maxTokens) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
    body.put("max_tokens", maxTokens);
    String responseText;
    try {
      responseText = http.post("openai.generate", endpoint, json.writeValueAsString(body));
    } catch (ProviderJsonHttp.Failure f) {
      throw new GenerationException(f.generationMessage("OpenAI-compatible API"));
    }
    JsonNode content;
    try {
      content = json.readTree(responseText).path("choices").path(0).path("message").path("content");
    } catch (RuntimeException e) {
      throw new GenerationException("OpenAI-compatible API malformed response");
    }
    if (!content.isString())
      throw new GenerationException("OpenAI-compatible API returned non-text content");
    return content.asString();
  }
}

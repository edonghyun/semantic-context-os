package io.scos.provider.gemini;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderJsonHttp;
import io.scos.spi.embedding.TextGenerator;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini generateContent 어댑터(요청:contents[0].parts[0].text, maxOutputTokens, flash 모델은
 * thinkingBudget 0). 응답은 thought가 아닌 parts의 text를 이어 붙인다. 실패 메시지에 본문을 싣지 않는다(H6).
 */
public final class GeminiTextGenerator implements TextGenerator {
  static final List<String> THINKING_OFF_MODELS =
      List.of("gemini-2.5-flash", "gemini-2.5-flash-lite");
  private final ObjectMapper json;
  private final ProviderJsonHttp http;
  private final URI endpoint;
  private final String model;

  public GeminiTextGenerator(
      String baseUrl,
      String apiKey,
      String model,
      ProviderCallExecutor executor,
      ObjectMapper json) {
    this.json = json;
    this.http = GeminiHttp.over(executor, apiKey);
    this.endpoint =
        URI.create(baseUrl.replaceAll("/$", "") + "/v1beta/models/" + model + ":generateContent");
    this.model = model;
  }

  @Override
  public String generate(String prompt, int maxTokens) {
    Map<String, Object> generation = new LinkedHashMap<>();
    generation.put("maxOutputTokens", maxTokens);
    if (THINKING_OFF_MODELS.contains(model))
      generation.put("thinkingConfig", Map.of("thinkingBudget", 0));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
    body.put("generationConfig", generation);
    String responseText;
    try {
      responseText = http.post("gemini.generate", endpoint, json.writeValueAsString(body));
    } catch (ProviderJsonHttp.Failure f) {
      throw new GenerationException(f.generationMessage("Gemini API"));
    }
    JsonNode parts;
    try {
      parts = json.readTree(responseText).path("candidates").path(0).path("content").path("parts");
    } catch (RuntimeException e) {
      throw new GenerationException("Gemini API malformed response");
    }
    StringBuilder out = new StringBuilder();
    for (JsonNode part : parts) {
      if (part.path("thought").asBoolean(false)) continue;
      JsonNode text = part.path("text");
      if (!text.isMissingNode() && !text.isTextual())
        throw new GenerationException("Gemini API returned non-text content");
      out.append(text.asString(""));
    }
    return out.toString();
  }
}

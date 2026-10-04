package com.apprentice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cliente minimo de Anthropic Messages API.
 * Si no hay clave configurada, devuelve null y el flujo sigue con reglas locales.
 */
@Component
public class AiClient {

  private final String apiKey;
  private final String model;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  public AiClient(
      @Value("${apprentice.ai.api-key}") String apiKey,
      @Value("${apprentice.ai.model}") String model) {
    this.apiKey = apiKey;
    this.model = model;
  }

  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }

  /** Pide texto al modelo. Devuelve null si la IA no esta configurada o falla. */
  public String complete(String system, String user) {
    if (!isConfigured()) return null;
    try {
      Map<String, Object> body = Map.of(
          "model", model,
          "max_tokens", 4096,
          "system", system,
          "messages", List.of(Map.of("role", "user", "content", user)));

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("https://api.anthropic.com/v1/messages"))
          .timeout(Duration.ofSeconds(60))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + apiKey)
          .header("anthropic-version", "2023-06-01")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
          .build();

      HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 300) return null;

      JsonNode response = mapper.readTree(res.body());
      String text = response.path("content").findValuesAsText("text").stream()
          .findFirst().orElse("").trim();
      return text.isEmpty() ? null : text;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return null;
    }
  }

  /** Pide JSON al modelo y lo parsea; null si no se puede. */
  public JsonNode completeJson(String system, String user) {
    String text = complete(system, user);
    if (text == null) return null;
    try {
      int start = text.indexOf('{');
      int end = text.lastIndexOf('}');
      if (start < 0 || end <= start) return null;
      return mapper.readTree(text.substring(start, end + 1));
    } catch (IOException e) {
      return null;
    }
  }
}

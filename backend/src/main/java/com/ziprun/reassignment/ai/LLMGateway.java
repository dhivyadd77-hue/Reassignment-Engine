package com.ziprun.reassignment.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Provider-specific HTTP wire format for Gemini, Groq, and Ollama.
 * Returns raw model text. Caller owns parsing, validation, and fallback.
 * Connect/read timeouts prevent the agentic loop from hanging on a dead LLM.
 */
@Component
public class LLMGateway {

	@Value("${llm.provider:gemini}")
	private String provider;

	@Value("${llm.api-key:}")
	private String apiKey;

	@Value("${llm.model:gemini-1.5-flash}")
	private String model;

	@Value("${llm.base-url:https://generativelanguage.googleapis.com}")
	private String baseUrl;

	@Value("${llm.connect-timeout-ms:3000}")
	private long connectTimeoutMs;

	@Value("${llm.read-timeout-ms:8000}")
	private long readTimeoutMs;

	private RestClient http;

	@jakarta.annotation.PostConstruct
	void initClient() {
		HttpClient jdkClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(connectTimeoutMs))
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkClient);
		requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
		this.http = RestClient.builder().requestFactory(requestFactory).build();
	}

	public String callLLM(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("LLM prompt must not be blank");
		}
		return switch (provider.toLowerCase()) {
			case "gemini" -> callGemini(prompt);
			case "groq" -> callOpenAICompatible(prompt, baseUrl + "/openai/v1/chat/completions");
			case "ollama" -> callOpenAICompatible(prompt, baseUrl + "/v1/chat/completions");
			default -> throw new IllegalStateException("Unknown provider: " + provider);
		};
	}

	@SuppressWarnings("unchecked")
	private String callGemini(String prompt) {
		var url = baseUrl + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;
		var body = Map.of("contents", List.of(
				Map.of("parts", List.of(Map.of("text", prompt)))));

		var resp = http.post().uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(Map.class);

		if (resp == null) {
			throw new RuntimeException("Gemini returned empty body");
		}
		try {
			var candidates = (List<?>) resp.get("candidates");
			if (candidates == null || candidates.isEmpty()) {
				throw new RuntimeException("Gemini returned no candidates");
			}
			var content = (Map<?, ?>) ((Map<?, ?>) candidates.get(0)).get("content");
			var parts = (List<?>) content.get("parts");
			return (String) ((Map<?, ?>) parts.get(0)).get("text");
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Gemini response parse failed", e);
		}
	}

	@SuppressWarnings("unchecked")
	private String callOpenAICompatible(String prompt, String url) {
		var body = Map.of(
				"model", model,
				"messages", List.of(Map.of(
						"role", "user",
						"content", prompt)));

		var resp = http.post().uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer " + apiKey)
				.body(body)
				.retrieve()
				.body(Map.class);

		if (resp == null) {
			throw new RuntimeException("LLM returned empty body");
		}
		try {
			var choices = (List<?>) resp.get("choices");
			if (choices == null || choices.isEmpty()) {
				throw new RuntimeException("LLM returned no choices");
			}
			var message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
			return (String) message.get("content");
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("LLM response parse failed", e);
		}
	}
}

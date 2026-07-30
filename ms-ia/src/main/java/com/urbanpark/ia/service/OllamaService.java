package com.urbanpark.ia.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP hacia Ollama (/api/generate) para asignación de plazas.
 */
@Service
public class OllamaService {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*?\\}");

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.model:llama3.2:3b}")
    private String model;

    @Value("${ollama.timeout-seconds:90}")
    private int timeoutSeconds;

    public boolean isReachable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(baseUrl) + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> res = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() >= 200 && res.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /** Generación con formato JSON (asignación de plaza). */
    public String generate(String prompt) throws Exception {
        return callGenerate(prompt, true, 200);
    }

    /** Texto libre para reportes narrativos. */
    public String generateText(String prompt) throws Exception {
        return callGenerate(prompt, false, 350);
    }

    private String callGenerate(String prompt, boolean jsonFormat, int numPredict) throws Exception {
        String formatPart = jsonFormat ? "\"format\": \"json\"," : "";
        String body = """
                {
                  "model": %s,
                  "prompt": %s,
                  "stream": false,
                  %s
                  "options": {
                    "temperature": 0.3,
                    "num_predict": %d
                  }
                }
                """.formatted(toJsonString(model), toJsonString(prompt), formatPart, numPredict);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(baseUrl) + "/api/generate"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Ollama HTTP " + response.statusCode() + ": " + response.body());
        }
        return extractResponseField(response.body());
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /** Extrae el campo "response" del JSON de Ollama. */
    static String extractResponseField(String json) {
        int key = json.indexOf("\"response\"");
        if (key < 0) return json;
        int colon = json.indexOf(':', key);
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return json;
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** Busca el primer objeto JSON en el texto del modelo. */
    public static String extractJsonObject(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.startsWith("{") && t.endsWith("}")) return t;
        Matcher m = JSON_OBJECT.matcher(t);
        if (m.find()) return m.group();
        return null;
    }

    public static String readJsonString(String json, String field) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String trimSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String toJsonString(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }
}

package com.example.joolini.stock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class KisOpenApiClient {
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final boolean enabled;
    private final String baseUrl;
    private final String appKey;
    private final String appSecret;
    private final long minimumRequestIntervalMs;
    private volatile String accessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;
    private long lastRequestAtMs;

    public KisOpenApiClient(
            ObjectMapper mapper,
            @Value("${joolini.kis.enabled:false}") boolean enabled,
            @Value("${joolini.kis.base-url:https://openapi.koreainvestment.com:9443}") String baseUrl,
            @Value("${joolini.kis.app-key:}") String appKey,
            @Value("${joolini.kis.app-secret:}") String appSecret,
            @Value("${joolini.kis.request-interval-ms:700}") long minimumRequestIntervalMs) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.minimumRequestIntervalMs = Math.max(500, minimumRequestIntervalMs);
    }

    public boolean isEnabled() {
        return enabled && !appKey.isBlank() && !appSecret.isBlank();
    }

    public String configurationMessage() {
        if (!enabled) return "KIS 연동이 꺼져 있어 모의 가격을 표시합니다.";
        if (appKey.isBlank() || appSecret.isBlank()) return "KIS 키가 없어 모의 가격을 표시합니다.";
        return "한국투자증권 현재가를 조회하고 있습니다.";
    }

    public Quote fetch(Market market, String symbol) {
        if (!isEnabled()) throw new IllegalStateException("KIS API is not configured");
        try {
            return market == Market.KR ? domestic(symbol) : overseas(symbol);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KisApiException("KIS API 호출이 중단되었습니다.", exception);
        } catch (IOException exception) {
            throw new KisApiException("KIS API에 연결하지 못했습니다.", exception);
        }
    }

    private Quote domestic(String symbol) throws IOException, InterruptedException {
        JsonNode output = get(
                "/uapi/domestic-stock/v1/quotations/inquire-price"
                        + "?FID_COND_MRKT_DIV_CODE=UN&FID_INPUT_ISCD=" + encode(symbol),
                "FHKST01010100").path("output");
        double price = number(output, "stck_prpr");
        double change = number(output, "prdy_ctrt");
        long volume = Math.round(number(output, "acml_vol"));
        double turnover = number(output, "acml_tr_pbmn");
        validate(symbol, price);
        return new Quote(price, change, volume, turnover > 0 ? turnover : price * volume, Instant.now());
    }

    private Quote overseas(String symbol) throws IOException, InterruptedException {
        JsonNode output = get(
                "/uapi/overseas-price/v1/quotations/price?AUTH=&EXCD=NAS&SYMB=" + encode(symbol),
                "HHDFS00000300").path("output");
        double price = firstNumber(output, "last", "last_price", "ovrs_nmix_prpr");
        double change = firstNumber(output, "rate", "prdy_ctrt", "change_rate");
        long volume = Math.round(firstNumber(output, "tvol", "acml_vol", "volume"));
        double turnover = firstNumber(output, "tamt", "acml_tr_pbmn", "trade_amount");
        validate(symbol, price);
        return new Quote(price, change, volume, turnover > 0 ? turnover : price * volume, Instant.now());
    }

    private JsonNode get(String path, String trId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("authorization", "Bearer " + token())
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", trId)
                .header("custtype", "P")
                .header("Content-Type", "application/json")
                .GET().build();
        HttpResponse<String> response = sendWithRateLimit(request);
        JsonNode body = mapper.readTree(response.body());
        if ("EGW00201".equals(body.path("msg_cd").asText())) {
            Thread.sleep(2_000);
            response = sendWithRateLimit(request);
            body = mapper.readTree(response.body());
        }
        if (response.statusCode() != 200 || !"0".equals(body.path("rt_cd").asText())) {
            throw new KisApiException("KIS 시세 조회 실패: " + body.path("msg1").asText("HTTP " + response.statusCode()));
        }
        return body;
    }

    private synchronized HttpResponse<String> sendWithRateLimit(HttpRequest request)
            throws IOException, InterruptedException {
        long waitMs = minimumRequestIntervalMs - (System.currentTimeMillis() - lastRequestAtMs);
        if (waitMs > 0) Thread.sleep(waitMs);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        lastRequestAtMs = System.currentTimeMillis();
        return response;
    }

    private synchronized String token() throws IOException, InterruptedException {
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) return accessToken;
        String body = mapper.writeValueAsString(Map.of(
                "grant_type", "client_credentials", "appkey", appKey, "appsecret", appSecret));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/oauth2/tokenP"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(response.body());
        if (response.statusCode() != 200 || json.path("access_token").asText().isBlank()) {
            throw new KisApiException("KIS 인증 실패: "
                    + json.path("error_description").asText("HTTP " + response.statusCode()));
        }
        accessToken = json.path("access_token").asText();
        tokenExpiresAt = Instant.now().plusSeconds(json.path("expires_in").asLong(86_400));
        return accessToken;
    }

    private double number(JsonNode node, String field) {
        try {
            return Double.parseDouble(node.path(field).asText("0").replace(",", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private double firstNumber(JsonNode node, String... fields) {
        for (String field : fields) {
            double value = number(node, field);
            if (value != 0) return value;
        }
        return 0;
    }

    private void validate(String symbol, double price) {
        if (price <= 0) throw new KisApiException("KIS 응답에 " + symbol + " 현재가가 없습니다.");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Quote(double price, double changePercent, long volume, double turnover, Instant asOf) {}

    public static class KisApiException extends RuntimeException {
        public KisApiException(String message) { super(message); }
        public KisApiException(String message, Throwable cause) { super(message, cause); }
    }
}

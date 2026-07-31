package com.example.joolini.stock;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1")
public class RecommendationController {
    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping("/recommendations")
    public Map<String, Object> recommendations(
            @RequestParam(defaultValue = "ALL") String market,
            @RequestParam(defaultValue = "ALL") String rating,
            @RequestParam(defaultValue = "") String query) {
        var items = service.findAll(market, rating, query);
        return Map.of(
                "items", items,
                "count", items.size(),
                "sort", "turnoverKrw,desc",
                "simulation", !service.isLiveQuoteMode(),
                "asOf", Instant.now());
    }

    @GetMapping("/stocks/{market}/{symbol}")
    public StockRecommendation stock(@PathVariable Market market, @PathVariable String symbol) {
        return service.findOne(market, symbol);
    }

    @GetMapping("/data-status")
    public Map<String, Object> dataStatus() {
        return Map.of(
                "mode", service.isLiveQuoteMode() ? "KIS_LIVE_QUOTES" : "SIMULATION",
                "healthy", !service.isLiveQuoteMode() || !service.dataMessage().startsWith("KIS "),
                "message", service.dataMessage(),
                "quoteRefreshSeconds", service.isLiveQuoteMode() ? 15 : 2,
                "rankingRefreshSeconds", 5,
                "fxRate", 1386.40,
                "asOf", Instant.now());
    }

    @GetMapping(value = "/stream/quotes", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<AutoCloseable> registration = new AtomicReference<>();
        try {
            registration.set(service.subscribe(items -> {
                try {
                    emitter.send(SseEmitter.event().name("recommendations").data(items));
                } catch (IOException exception) {
                    emitter.complete();
                }
            }));
        } catch (RuntimeException exception) {
            emitter.completeWithError(exception);
        }
        Runnable cleanup = () -> closeQuietly(registration.get());
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        return emitter;
    }

    @ExceptionHandler(StockNotFoundException.class)
    public Map<String, Object> notFound(StockNotFoundException exception) {
        return Map.of("error", "STOCK_NOT_FOUND", "message", exception.getMessage());
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // No resource remains after subscriber removal.
        }
    }
}

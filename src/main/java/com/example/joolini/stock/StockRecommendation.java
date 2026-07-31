package com.example.joolini.stock;

import java.time.Instant;
import java.util.List;

public record StockRecommendation(
        String symbol,
        String name,
        Market market,
        double price,
        String currency,
        double changePercent,
        long volume,
        double turnover,
        double turnoverKrw,
        Rating rating,
        int score,
        int confidence,
        List<String> reasons,
        SignalBreakdown signals,
        Instant asOf,
        Instant fxAsOf,
        boolean simulation,
        String quoteSource
) {
    public record SignalBreakdown(
            int analyst,
            int flow,
            int momentum,
            String flowLabel,
            String flowFreshness
    ) {}
}

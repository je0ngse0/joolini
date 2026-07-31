package com.example.joolini.stock;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class RecommendationService {
    private static final double USD_KRW = 1_386.40;
    private final Map<String, MutableStock> stocks = new LinkedHashMap<>();
    private final List<Consumer<List<StockRecommendation>>> subscribers = new CopyOnWriteArrayList<>();
    private final SplittableRandom random = new SplittableRandom(20260731);
    private final KisOpenApiClient kisClient;
    private final AtomicReference<String> lastKisError = new AtomicReference<>();
    private volatile Instant fxAsOf = Instant.now();

    public RecommendationService(KisOpenApiClient kisClient) {
        this.kisClient = kisClient;
    }

    @PostConstruct
    void initialize() {
        add("005930", "삼성전자", Market.KR, 83_700, 19_824_510, 78, 82, 73);
        add("000660", "SK하이닉스", Market.KR, 221_500, 6_884_302, 86, 79, 88);
        add("373220", "LG에너지솔루션", Market.KR, 357_000, 692_118, 62, 48, 55);
        add("035420", "NAVER", Market.KR, 211_000, 1_553_020, 75, 69, 71);
        add("247540", "에코프로비엠", Market.KR, 184_600, 1_221_904, 45, 34, 42);
        add("AAPL", "Apple", Market.US, 232.41, 48_255_104, 76, 61, 73);
        add("NVDA", "NVIDIA", Market.US, 121.38, 278_404_210, 91, 74, 89);
        add("MSFT", "Microsoft", Market.US, 448.12, 20_985_004, 81, 68, 77);
        add("TSLA", "Tesla", Market.US, 219.80, 91_402_120, 49, 44, 58);
        add("AMZN", "Amazon", Market.US, 186.32, 41_881_934, 72, 59, 69);
    }

    private void add(String symbol, String name, Market market, double price, long volume,
                     int analyst, int flow, int momentum) {
        stocks.put(key(market, symbol),
                new MutableStock(symbol, name, market, price, price, volume, analyst, flow, momentum));
    }

    public List<StockRecommendation> findAll(String market, String rating, String query) {
        return snapshot().stream()
                .filter(stock -> market == null || market.equalsIgnoreCase("ALL")
                        || stock.market().name().equalsIgnoreCase(market))
                .filter(stock -> rating == null || rating.equalsIgnoreCase("ALL")
                        || stock.rating().name().equalsIgnoreCase(rating))
                .filter(stock -> query == null || query.isBlank()
                        || stock.symbol().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                        || stock.name().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .toList();
    }

    public StockRecommendation findOne(Market market, String symbol) {
        MutableStock stock = stocks.get(key(market, symbol));
        if (stock == null) {
            throw new StockNotFoundException(symbol);
        }
        return toView(stock);
    }

    public AutoCloseable subscribe(Consumer<List<StockRecommendation>> subscriber) {
        subscribers.add(subscriber);
        subscriber.accept(snapshot());
        return () -> subscribers.remove(subscriber);
    }

    public List<StockRecommendation> snapshot() {
        return stocks.values().stream()
                .map(this::toView)
                .sorted(Comparator.comparingDouble(StockRecommendation::turnoverKrw).reversed()
                        .thenComparing(Comparator.comparingLong(StockRecommendation::volume).reversed())
                        .thenComparing(Comparator.comparingDouble(StockRecommendation::changePercent).reversed())
                        .thenComparing(StockRecommendation::symbol))
                .toList();
    }

    @Scheduled(fixedRateString = "${joolini.simulation.tick-rate-ms:2000}")
    void tick() {
        if (kisClient != null && kisClient.isEnabled()) return;
        stocks.values().forEach(stock -> {
            double volatility = stock.market == Market.KR ? 0.0025 : 0.0035;
            double move = (random.nextDouble() - 0.48) * volatility;
            stock.price = roundPrice(stock.market, stock.price * (1 + move));
            stock.volume += random.nextLong(8_000, stock.market == Market.KR ? 120_000 : 350_000);
            stock.momentum = clamp(stock.momentum + random.nextInt(-2, 3));
            if (random.nextDouble() < .18) {
                stock.flow = clamp(stock.flow + random.nextInt(-2, 3));
            }
        });
        List<StockRecommendation> update = snapshot();
        subscribers.forEach(subscriber -> subscriber.accept(update));
    }

    @Scheduled(
            initialDelayString = "${joolini.kis.initial-delay-ms:1000}",
            fixedDelayString = "${joolini.kis.poll-rate-ms:15000}")
    void refreshKisQuotes() {
        if (kisClient == null || !kisClient.isEnabled()) return;
        stocks.values().forEach(stock -> {
            try {
                KisOpenApiClient.Quote quote = kisClient.fetch(stock.market, stock.symbol);
                stock.price = quote.price();
                stock.changePercent = quote.changePercent();
                stock.volume = quote.volume();
                stock.turnover = quote.turnover();
                stock.asOf = quote.asOf();
                stock.liveQuote = true;
                lastKisError.set(null);
            } catch (KisOpenApiClient.KisApiException exception) {
                lastKisError.set(exception.getMessage());
            }
        });
        List<StockRecommendation> update = snapshot();
        subscribers.forEach(subscriber -> subscriber.accept(update));
    }

    public boolean isLiveQuoteMode() {
        return kisClient != null && kisClient.isEnabled();
    }

    public String dataMessage() {
        String error = lastKisError.get();
        if (error != null) return error;
        return kisClient == null ? "모의 가격 모드입니다." : kisClient.configurationMessage();
    }

    private StockRecommendation toView(MutableStock stock) {
        double change = stock.liveQuote
                ? stock.changePercent
                : ((stock.price - stock.open) / stock.open) * 100;
        double turnover = stock.liveQuote && stock.turnover > 0
                ? stock.turnover
                : stock.price * stock.volume;
        double turnoverKrw = stock.market == Market.KR ? turnover : turnover * USD_KRW;
        int score = stock.market == Market.KR
                ? (int) Math.round(stock.analyst * .35 + stock.flow * .40 + stock.momentum * .25)
                : (int) Math.round(stock.analyst * .40 + stock.flow * .30 + stock.momentum * .30);
        Rating rating = score >= 70 ? Rating.RECOMMEND : score >= 45 ? Rating.WATCH : Rating.AVOID;
        int confidence = Math.min(96, 62 + Math.abs(score - 57) / 2);
        String flowLabel = stock.market == Market.KR ? "외국인·기관 수급" : "13F·내부자 신호";
        String freshness = stock.market == Market.KR ? "장중 모의 집계" : "13F 분기 공시·Form 4";
        List<String> reasons = reasons(stock, score);
        return new StockRecommendation(
                stock.symbol, stock.name, stock.market, stock.price,
                stock.market == Market.KR ? "KRW" : "USD", change, stock.volume,
                turnover, turnoverKrw, rating, score, confidence, reasons,
                new StockRecommendation.SignalBreakdown(
                        stock.analyst, stock.flow, stock.momentum, flowLabel, freshness),
                stock.liveQuote ? stock.asOf : Instant.now(), fxAsOf,
                !stock.liveQuote,
                stock.liveQuote ? "한국투자증권 Open API" : "로컬 모의 시세");
    }

    private List<String> reasons(MutableStock stock, int score) {
        List<String> result = new ArrayList<>();
        result.add(stock.analyst >= 70 ? "애널리스트 컨센서스가 긍정적이에요"
                : "애널리스트 전망의 확신이 아직 낮아요");
        result.add(stock.flow >= 65
                ? (stock.market == Market.KR ? "외국인·기관 매수 흐름이 우세해요" : "기관·내부자 신호가 우호적이에요")
                : (stock.market == Market.KR ? "수급의 추가 확인이 필요해요" : "기관 공시 신호가 뚜렷하지 않아요"));
        result.add(stock.momentum >= 70 ? "가격과 거래량 모멘텀이 강해요"
                : stock.momentum < 50 ? "단기 모멘텀이 약해요" : "모멘텀은 중립 구간이에요");
        if (score < 45) result.add("현재 지표 조합에서는 위험 대비 매력이 낮아요");
        return result;
    }

    private double roundPrice(Market market, double price) {
        return market == Market.KR ? Math.round(price / 100.0) * 100.0 : Math.round(price * 100.0) / 100.0;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String key(Market market, String symbol) {
        return market.name() + ":" + symbol.toUpperCase(Locale.ROOT);
    }

    private static final class MutableStock {
        final String symbol;
        final String name;
        final Market market;
        final double open;
        final int analyst;
        double price;
        long volume;
        int flow;
        int momentum;
        double changePercent;
        double turnover;
        Instant asOf = Instant.now();
        boolean liveQuote;

        private MutableStock(String symbol, String name, Market market, double price, double open,
                             long volume, int analyst, int flow, int momentum) {
            this.symbol = symbol;
            this.name = name;
            this.market = market;
            this.price = price;
            this.open = open;
            this.volume = volume;
            this.analyst = analyst;
            this.flow = flow;
            this.momentum = momentum;
        }
    }
}

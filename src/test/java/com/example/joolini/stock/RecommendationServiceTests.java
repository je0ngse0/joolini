package com.example.joolini.stock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationServiceTests {
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(null);
        service.initialize();
    }

    @Test
    void recommendationsAreSortedByKrwTurnoverDescending() {
        List<StockRecommendation> items = service.snapshot();

        assertThat(items).isNotEmpty();
        assertThat(items)
                .extracting(StockRecommendation::turnoverKrw)
                .isSortedAccordingTo((left, right) -> Double.compare(right, left));
    }

    @Test
    void marketAndRatingFiltersCanBeCombined() {
        List<StockRecommendation> items = service.findAll("KR", "RECOMMEND", "");

        assertThat(items).isNotEmpty();
        assertThat(items).allMatch(item -> item.market() == Market.KR);
        assertThat(items).allMatch(item -> item.rating() == Rating.RECOMMEND);
    }

    @Test
    void searchMatchesNameOrSymbol() {
        assertThat(service.findAll("ALL", "ALL", "하이닉스"))
                .extracting(StockRecommendation::symbol)
                .containsExactly("000660");
        assertThat(service.findAll("ALL", "ALL", "AAPL"))
                .extracting(StockRecommendation::name)
                .containsExactly("Apple");
    }

    @Test
    void unknownStockFailsClearly() {
        assertThatThrownBy(() -> service.findOne(Market.KR, "999999"))
                .isInstanceOf(StockNotFoundException.class)
                .hasMessageContaining("999999");
    }
}

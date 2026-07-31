package com.example.joolini.stock;

public enum Rating {
    RECOMMEND("추천"),
    WATCH("관망"),
    AVOID("비추천"),
    UNRATED("판정 보류");

    private final String label;

    Rating(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

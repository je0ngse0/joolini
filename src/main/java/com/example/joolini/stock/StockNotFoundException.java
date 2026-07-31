package com.example.joolini.stock;

public class StockNotFoundException extends RuntimeException {
    public StockNotFoundException(String symbol) {
        super("종목을 찾을 수 없습니다: " + symbol);
    }
}

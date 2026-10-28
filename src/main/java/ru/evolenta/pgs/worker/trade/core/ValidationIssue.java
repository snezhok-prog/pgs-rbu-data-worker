package ru.evolenta.pgs.worker.trade.core;

public record ValidationIssue(String code, String sheet, int row, String column, String message) {}

package ru.evolenta.pgs.worker.trade.core;

import java.util.List;

public record ValidationReport(List<ValidationIssue> issues) {
    public ValidationReport {
        issues = List.copyOf(issues);
    }

    public boolean valid() {
        return issues.isEmpty();
    }
}

package com.github.ovaware.dealmaker.deal;

import java.util.List;

public record ParseResult(List<DealClause> clauses, List<String> errors) {
    public boolean accepted() {
        return errors.isEmpty() && !clauses.isEmpty();
    }

    public static ParseResult rejected(String error) {
        return new ParseResult(List.of(), List.of(error));
    }
}

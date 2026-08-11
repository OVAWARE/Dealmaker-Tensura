package com.github.ovaware.dealmaker.ai;

import com.github.ovaware.dealmaker.deal.ParseResult;

import java.util.concurrent.CompletableFuture;

public interface AsyncDealParser {
    CompletableFuture<ParseResult> parse(String contractText);
}

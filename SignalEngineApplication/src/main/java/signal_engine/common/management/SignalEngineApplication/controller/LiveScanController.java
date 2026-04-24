package signal_engine.common.management.SignalEngineApplication.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import signal_engine.common.management.SignalEngineApplication.Service.LiveScanService;
import signal_engine.common.management.SignalEngineApplication.model.LiveScanResult;

import java.util.List;
import java.util.Map;

/**
 * Live scan endpoints — current indicator state, no historical backtest needed.
 *
 * GET /api/v1/live/scan?index=NIFTY+50&topN=10     — scan full index, return setups
 * GET /api/v1/live/analyse?symbol=HDFCBANK          — single stock deep analysis
 * GET /api/v1/live/pipeline?index=NIFTY+50&topN=10  — live scan + Layer 2 LLM on setups
 */
@RestController
@RequestMapping("/api/v1/live")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class LiveScanController {

    private final LiveScanService     liveScanService;
    private final signal_engine.common.management.SignalEngineApplication.Service.MarketDataClient marketDataClient;

    // ── Full index scan ───────────────────────────────────────────────────────
    //
    // Returns only stocks currently in a setup (uptrend + RSI not extended).
    // Much faster than backtest scan — ~2 min for 50 stocks vs 20-30 min.
    //
    @GetMapping("/scan")
    public ResponseEntity<?> scan(
            @RequestParam(defaultValue = "NIFTY 50") String index,
            @RequestParam(defaultValue = "10")       int topN,
            @RequestParam(defaultValue = "NS")       String exchange
    ) {
        try {
            List<LiveScanResult> results = liveScanService.scan(index, topN);
            long setupCount = results.stream().filter(LiveScanResult::isSetup).count();
            return ResponseEntity.ok(Map.of(
                    "index",   index,
                    "scanned", results.size(),
                    "setups",  setupCount,
                    "results", results
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Single stock ──────────────────────────────────────────────────────────
    //
    // Detailed indicator breakdown for one stock.
    //
    @GetMapping("/analyse")
    public ResponseEntity<?> analyse(@RequestParam String symbol) {
        if (symbol == null || !symbol.matches("[A-Z][A-Z0-9\\-&]{0,19}"))
            return ResponseEntity.badRequest().body("Invalid symbol");
        try {
            return ResponseEntity.ok(liveScanService.analyseOne(symbol.toUpperCase()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Live pipeline — scan + Layer 2 on setups ──────────────────────────────
    //
    // Step 1: Live indicator scan — find stocks in current setup
    // Step 2: Layer 2 LLM analysis on the setups only (fundamentals + sentiment)
    // Result: A short list of high-conviction trades with entry/stop/target
    //
    // This is the correct pipeline for real trading — uses 3mo data for indicators,
    // current news/fundamentals for conviction. Not 5-year historical backtest.
    //
    @GetMapping("/pipeline")
    public ResponseEntity<?> livePipeline(
            @RequestParam(defaultValue = "NIFTY 50") String index,
            @RequestParam(defaultValue = "10")       int topN,
            @RequestParam(defaultValue = "NS")       String exchange
    ) {
        try {
            // Step 1 — live indicator scan, get all setups (no topN limit yet)
            List<LiveScanResult> allResults = liveScanService.scan(index, 0);
            List<LiveScanResult> setups = allResults.stream()
                    .filter(LiveScanResult::isSetup)
                    .limit(topN)
                    .collect(java.util.stream.Collectors.toList());

            if (setups.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "index",   index,
                        "message", "No setups found in current market conditions",
                        "layer1_setups", List.of(),
                        "layer2", Map.of("results", List.of(), "passed", 0)
                ));
            }

            // Step 2 — Layer 2 on setups only
            String symbols = setups.stream()
                    .map(LiveScanResult::getSymbol)
                    .collect(java.util.stream.Collectors.joining(","));

            Map<String, Object> layer2 = marketDataClient.fetchLayer2Scan(symbols);

            return ResponseEntity.ok(Map.of(
                    "pipeline",      "live_scan → layer2",
                    "index",         index,
                    "layer1_setups", setups,
                    "layer2",        layer2
            ));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }
}

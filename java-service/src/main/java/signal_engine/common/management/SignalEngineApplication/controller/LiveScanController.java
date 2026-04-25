package signal_engine.common.management.SignalEngineApplication.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import signal_engine.common.management.SignalEngineApplication.Service.DailyScanScheduler;
import signal_engine.common.management.SignalEngineApplication.Service.LiveScanService;
import signal_engine.common.management.SignalEngineApplication.Service.MarketRegimeService;
import signal_engine.common.management.SignalEngineApplication.model.LiveScanResult;
import signal_engine.common.management.SignalEngineApplication.model.MarketRegime;
import signal_engine.common.management.SignalEngineApplication.model.ScanStrictness;

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

    private final LiveScanService       liveScanService;
    private final DailyScanScheduler    dailyScanScheduler;
    private final MarketRegimeService   marketRegimeService;
    private final signal_engine.common.management.SignalEngineApplication.Service.MarketDataClient marketDataClient;

    // ── Market regime ────────────────────────────────────────────────────────────
    //
    // GET /api/v1/live/regime
    // Returns current market regime — check this before running the pipeline.
    //
    @GetMapping("/regime")
    public ResponseEntity<?> regime() {
        try {
            MarketRegime r = marketRegimeService.analyse();
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Manual trigger for daily scan (for testing) ──────────────────────────
    //
    // POST /api/v1/live/scan/trigger
    // Runs the same logic as the 9pm scheduler immediately.
    // Use to test email without waiting for the scheduled time.
    //
    @org.springframework.web.bind.annotation.PostMapping("/scan/trigger")
    public ResponseEntity<?> triggerScan() {
        try {
            dailyScanScheduler.runDailyScan();
            return ResponseEntity.ok(Map.of("message", "Daily scan triggered — check logs and email"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Cache status (proxies to Python service) ──────────────────────────────
    //
    // GET  /api/v1/live/cache/status           — show cache hit rate and keys
    // POST /api/v1/live/cache/invalidate/symbols?index=NIFTY+50
    // POST /api/v1/live/cache/invalidate/candles?symbol=HDFCBANK
    // POST /api/v1/live/cache/invalidate/all
    //
    @GetMapping("/cache/status")
    public ResponseEntity<?> cacheStatus() {
        try {
            String url = marketDataClient.getDataServiceUrl() + "/cache/status";
            return ResponseEntity.ok(
                    new org.springframework.web.client.RestTemplate().getForObject(url, Object.class));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Could not reach Python service: " + e.getMessage());
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/cache/invalidate/symbols")
    public ResponseEntity<?> invalidateSymbols(
            @RequestParam(required = false) String index) {
        try {
            String url = marketDataClient.getDataServiceUrl() + "/cache/invalidate/symbols"
                    + (index != null ? "?index=" + java.net.URLEncoder.encode(index, "UTF-8") : "");
            return ResponseEntity.ok(
                    new org.springframework.web.client.RestTemplate().postForObject(url, null, Object.class));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/cache/invalidate/candles")
    public ResponseEntity<?> invalidateCandles(
            @RequestParam(required = false) String symbol) {
        try {
            String url = marketDataClient.getDataServiceUrl() + "/cache/invalidate/candles"
                    + (symbol != null ? "?symbol=" + symbol : "");
            return ResponseEntity.ok(
                    new org.springframework.web.client.RestTemplate().postForObject(url, null, Object.class));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/cache/invalidate/all")
    public ResponseEntity<?> invalidateAll() {
        try {
            String url = marketDataClient.getDataServiceUrl() + "/cache/invalidate/all";
            return ResponseEntity.ok(
                    new org.springframework.web.client.RestTemplate().postForObject(url, null, Object.class));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Full index scan ───────────────────────────────────────────────────────
    //
    // Returns only stocks currently in a setup (uptrend + RSI not extended).
    // Much faster than backtest scan — ~2 min for 50 stocks vs 20-30 min.
    //
    @GetMapping("/scan")
    public ResponseEntity<?> scan(
            @RequestParam(defaultValue = "NIFTY 50")  String index,
            @RequestParam(defaultValue = "10")         int topN,
            @RequestParam(defaultValue = "NS")         String exchange,
            @RequestParam(defaultValue = "MODERATE")   String strictness
    ) {
        try {
            ScanStrictness level = ScanStrictness.fromString(strictness);
            List<LiveScanResult> results = liveScanService.scan(index, topN, null, level);
            long setupCount = results.stream().filter(LiveScanResult::isSetup).count();
            return ResponseEntity.ok(Map.of(
                    "index",       index,
                    "strictness",  level.name(),
                    "description", level.description,
                    "scanned",     results.size(),
                    "setups",      setupCount,
                    "results",     results
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
            @RequestParam(defaultValue = "NIFTY 50")  String index,
            @RequestParam(defaultValue = "10")         int topN,
            @RequestParam(defaultValue = "NS")         String exchange,
            @RequestParam(defaultValue = "MODERATE")   String strictness,
            jakarta.servlet.http.HttpServletRequest    request
    ) {
        try {
            // Step 0 — market regime check
            MarketRegime regime = marketRegimeService.analyse();
            if (!regime.isTradeable()) {
                return ResponseEntity.ok(Map.of(
                        "regime",       regime,
                        "blocked",      true,
                        "message",      "Pipeline blocked: " + regime.getReason(),
                        "layer1_setups", List.of(),
                        "layer2",        Map.of("results", List.of(), "passed", 0)
                ));
            }

            // Step 1 — live indicator scan with regime-adjusted threshold
            int adjustedMinScore = regime.getMinSetupScore();
            ScanStrictness level = ScanStrictness.fromString(
                    request != null ? request.getParameter("strictness") : "MODERATE");
            List<LiveScanResult> allResults = liveScanService.scan(index, 0, regime, level);
            List<LiveScanResult> setups = allResults.stream()
                    .filter(LiveScanResult::isSetup)
                    .filter(r -> r.getSetupScore() >= adjustedMinScore)
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
                    "regime",        regime,
                    "layer1_setups", setups,
                    "layer2",        layer2
            ));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }
}
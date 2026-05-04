package signal_engine.common.management.SignalEngineApplication.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import signal_engine.common.management.SignalEngineApplication.Service.BacktestService;
import signal_engine.common.management.SignalEngineApplication.Service.MarketDataClient;
import signal_engine.common.management.SignalEngineApplication.Service.Nifty50ScannerService;
import signal_engine.common.management.SignalEngineApplication.Service.StockDataService;
import signal_engine.common.management.SignalEngineApplication.Service.WalkForwardService;
import signal_engine.common.management.SignalEngineApplication.model.BacktestResult;
import signal_engine.common.management.SignalEngineApplication.model.Candle;
import signal_engine.common.management.SignalEngineApplication.model.Nifty50ScanResult;
import signal_engine.common.management.SignalEngineApplication.model.WalkForwardConfig;
import signal_engine.common.management.SignalEngineApplication.model.WalkForwardResult;

@RestController
@RequestMapping("/api/v1/backtest")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService       backtestService;
    private final StockDataService      stockDataService;
    private final MarketDataClient      marketDataClient;
    private final Nifty50ScannerService nifty50ScannerService;
    private final WalkForwardService    walkForwardService;

    // ── Single stock — live data ───────────────────────────────────────────────
    // GET /api/v1/backtest/run?stock=HDFCBANK&period=1y
    @GetMapping("/run")
    public ResponseEntity<?> runBacktest(
            @RequestParam String stock,
            @RequestParam(defaultValue = "1y") String period,
            @RequestParam(defaultValue = "NS") String exchange
    ) {
        if (!isValidSymbol(stock))      return badRequest("Invalid symbol");
        if (!isValidPeriod(period))     return badRequest("Invalid period. Use: 1mo, 3mo, 6mo, 1y, 2y, 5y");
        if (!isValidExchange(exchange)) return badRequest("Invalid exchange. Use NS or BO");

        try {
            List<Candle> candles = marketDataClient.fetchCandles(stock, period, exchange);
            BacktestResult result = backtestService.run(stock, candles);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Single stock — CSV fallback ───────────────────────────────────────────
    // GET /api/v1/backtest/run-csv?stock=INFY&fileName=INFY.csv
    @GetMapping("/run-csv")
    public ResponseEntity<?> runBacktestFromCSV(
            @RequestParam String stock,
            @RequestParam String fileName
    ) {
        if (!fileName.matches("[A-Z0-9_]+\\.csv"))
            return badRequest("Invalid file name. Only uppercase CSV files e.g. INFY.csv");

        try {
            List<Candle> candles = stockDataService.loadFromCSV(fileName);
            BacktestResult result = backtestService.run(stock, candles);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Nifty scan ────────────────────────────────────────────────────────────
    // GET /api/v1/backtest/nifty50/scan?period=1y&topN=10
    @GetMapping("/nifty50/scan")
    public ResponseEntity<?> scanNifty50(
            @RequestParam(defaultValue = "1y")      String period,
            @RequestParam(defaultValue = "NS")      String exchange,
            @RequestParam(defaultValue = "0")       int topN,
            @RequestParam(defaultValue = "NIFTY 50") String index
    ) {
        if (!isValidPeriod(period))     return badRequest("Invalid period");
        if (!isValidExchange(exchange)) return badRequest("Invalid exchange. Use NS or BO");

        try {
            List<Nifty50ScanResult> results = nifty50ScannerService.scanAll(period, exchange, topN, index);
            return ResponseEntity.ok(results);
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Top N performers ──────────────────────────────────────────────────────
    // GET /api/v1/backtest/nifty50/top?topN=10
    @GetMapping("/nifty50/top")
    public ResponseEntity<?> topNifty50(
            @RequestParam(defaultValue = "1y")      String period,
            @RequestParam(defaultValue = "NS")      String exchange,
            @RequestParam(defaultValue = "10")      int topN,
            @RequestParam(defaultValue = "NIFTY 50") String index
    ) {
        if (!isValidPeriod(period))     return badRequest("Invalid period");
        if (!isValidExchange(exchange)) return badRequest("Invalid exchange. Use NS or BO");

        try {
            List<Nifty50ScanResult> results = nifty50ScannerService.scanAll(period, exchange, topN, index);
            return ResponseEntity.ok(results);
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Layer 2 — single stock ────────────────────────────────────────────────
    // GET /api/v1/backtest/layer2/analyse?stock=TECHM
    // Proxies to Python microservice /layer2/analyse
    @GetMapping("/layer2/analyse")
    public ResponseEntity<?> layer2Analyse(@RequestParam String stock) {
        if (!isValidSymbol(stock)) return badRequest("Invalid symbol");
        try {
            Map<String, Object> result = marketDataClient.fetchLayer2Analysis(stock);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Layer 2 — batch scan ──────────────────────────────────────────────────
    // GET /api/v1/backtest/layer2/scan?stocks=TECHM,HDFCBANK
    // Proxies to Python microservice /layer2/scan
    @GetMapping("/layer2/scan")
    public ResponseEntity<?> layer2Scan(@RequestParam String stocks) {
        if (stocks == null || stocks.isBlank()) return badRequest("stocks param required");
        try {
            Map<String, Object> result = marketDataClient.fetchLayer2Scan(stocks);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Full pipeline — Layer 1 → Layer 2 ────────────────────────────────────
    //
    // GET /api/v1/backtest/pipeline/run?period=5y&topN=8&index=NIFTY+100
    //
    // 1. Fetches index constituents from Python service
    // 2. Runs Layer 1 backtest on all stocks, keeps topN by return%
    // 3. Runs Layer 2 LLM analysis on shortlist
    // 4. Returns combined result — UI calls POST /api/v1/trades/pipeline to save signals
    //
    @GetMapping("/pipeline/run")
    public ResponseEntity<?> runPipeline(
            @RequestParam(defaultValue = "1y")       String period,
            @RequestParam(defaultValue = "8")        int    topN,
            @RequestParam(defaultValue = "NS")       String exchange,
            @RequestParam(defaultValue = "NIFTY 50") String index
    ) {
        if (!isValidPeriod(period))     return badRequest("Invalid period. Use: 1mo, 3mo, 6mo, 1y, 2y, 5y");
        if (!isValidExchange(exchange)) return badRequest("Invalid exchange. Use NS or BO");
        if (topN < 1 || topN > 50)     return badRequest("topN must be between 1 and 50");

        try {
            // Step 1 — Layer 1: scan the index and get top N
            List<Nifty50ScanResult> layer1Results =
                    nifty50ScannerService.scanAll(period, exchange, topN, index);

            List<String> shortlist = layer1Results.stream()
                    .map(Nifty50ScanResult::getSymbol)
                    .collect(java.util.stream.Collectors.toList());

            // Step 2 — Layer 2: LLM analysis on shortlist
            Map<String, Object> layer2Results = marketDataClient.fetchLayer2Scan(String.join(",", shortlist));

            // Step 3 — Return combined result
            return ResponseEntity.ok(Map.of(
                    "pipeline",        "layer1 → layer2",
                    "index",           index,
                    "period",          period,
                    "layer1_shortlist", shortlist,
                    "layer2",          layer2Results
            ));

        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Walk-Forward Validation ───────────────────────────────────────────────
    // POST /api/v1/backtest/walk-forward?stock=HDFCBANK&period=5y
    //
    // Body (optional — defaults applied if missing):
    //   {
    //     "trainDays":      504,
    //     "testDays":       126,
    //     "rollDays":       126,
    //     "riskPerTrade":   0.01,
    //     "atrMultiplier":  2.0,
    //     "takeProfitRR":   2.0,
    //     "entrySlippage":  0.0025,
    //     "exitSlippage":   0.0025,
    //     "initialCapital": 100000
    //   }
    @PostMapping("/walk-forward")
    public ResponseEntity<?> walkForward(
            @RequestParam String stock,
            @RequestParam(defaultValue = "5y")  String period,
            @RequestParam(defaultValue = "NS")  String exchange,
            @RequestBody(required = false) WalkForwardConfig config
    ) {
        if (!isValidSymbol(stock))      return badRequest("Invalid symbol");
        if (!isValidPeriod(period))     return badRequest("Invalid period. Use: 1mo, 3mo, 6mo, 1y, 2y, 5y");
        if (!isValidExchange(exchange)) return badRequest("Invalid exchange. Use NS or BO");

        WalkForwardConfig cfg = config == null ? new WalkForwardConfig() : config;

        try {
            List<Candle> candles = marketDataClient.fetchCandles(stock, period, exchange);
            WalkForwardResult result = walkForwardService.run(stock, candles, cfg);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isValidSymbol(String s) {
        return s != null && s.matches("[A-Z][A-Z0-9\\-&]{0,19}");
    }

    private boolean isValidPeriod(String p) {
        return p != null && p.matches("1mo|3mo|6mo|1y|2y|5y");
    }

    private boolean isValidExchange(String e) {
        return "NS".equals(e) || "BO".equals(e);
    }

    private ResponseEntity<String> badRequest(String message) {
        return ResponseEntity.badRequest().body(message);
    }
}
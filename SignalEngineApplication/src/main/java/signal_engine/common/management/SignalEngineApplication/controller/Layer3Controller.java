package signal_engine.common.management.SignalEngineApplication.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import signal_engine.common.management.SignalEngineApplication.Service.Layer3Service;
import signal_engine.common.management.SignalEngineApplication.Service.TradeService;
import signal_engine.common.management.SignalEngineApplication.model.Layer3Result;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Layer 3 endpoints — precise trade parameter refinement.
 *
 * GET /api/v1/layer3/refine?symbol=HDFCBANK
 *     → Full S/R analysis, entry/stop/target, candlestick pattern
 *
 * POST /api/v1/layer3/refine-and-save?symbol=HDFCBANK
 *     → Refine + push precise parameters to trade tracker signal
 *
 * GET /api/v1/layer3/scan?symbols=HDFCBANK,TITAN,SBIN
 *     → Batch refine a list of Layer 2 passed stocks
 */
@RestController
@RequestMapping("/api/v1/layer3")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class Layer3Controller {

    private final Layer3Service layer3Service;
    private final TradeService  tradeService;

    // ── Single stock refinement ───────────────────────────────────────────────

    @GetMapping("/refine")
    public ResponseEntity<?> refine(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "OTHER") String sector
    ) {
        if (symbol == null || symbol.isBlank())
            return ResponseEntity.badRequest().body("symbol is required");

        try {
            Layer3Result result = layer3Service.refine(symbol.toUpperCase(), sector.toUpperCase());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Refine and auto-save to trade tracker ─────────────────────────────────
    //
    // Refines a stock and updates its SIGNAL record in the trade tracker
    // with the precise entry/stop/target from Layer 3 analysis.
    //
    @PostMapping("/refine-and-save")
    public ResponseEntity<?> refineAndSave(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "OTHER") String sector
    ) {
        if (symbol == null || symbol.isBlank())
            return ResponseEntity.badRequest().body("symbol is required");

        try {
            Layer3Result l3 = layer3Service.refine(symbol.toUpperCase(), sector.toUpperCase());

            if (l3.isError()) {
                return ResponseEntity.ok(Map.of(
                        "status",  "error",
                        "symbol",  symbol,
                        "message", l3.getErrorMessage()
                ));
            }

            if ("AVOID".equals(l3.getEntrySignal())) {
                return ResponseEntity.ok(Map.of(
                        "status",  "skipped",
                        "symbol",  symbol,
                        "reason",  "R:R too poor — " + l3.getTradeNote()
                ));
            }

            // Update existing signal in trade tracker with precise parameters
            Map<String, Object> update = Map.of(
                    "symbol",     symbol,
                    "entryLow",   l3.getEntryLow(),
                    "entryHigh",  l3.getEntryHigh(),
                    "stopLoss",   l3.getStopLoss(),
                    "target",     l3.getTarget1(),
                    "maxHoldDays", l3.getSuggestedHoldDays(),
                    "entryNote",  l3.getTradeNote()
            );

            // Try to update existing signal; create new one if not found
            try {
                tradeService.updateSignalFromLayer3(symbol, update);
                return ResponseEntity.ok(Map.of(
                        "status",   "updated",
                        "symbol",   symbol,
                        "layer3",   l3,
                        "message",  "Signal updated with Layer 3 parameters"
                ));
            } catch (Exception e) {
                // No existing signal — create one
                tradeService.createSignalManual(new java.util.HashMap<>(Map.of(
                        "symbol",         symbol,
                        "companyName",    symbol,
                        "entryLow",       l3.getEntryLow(),
                        "entryHigh",      l3.getEntryHigh(),
                        "stopLoss",       l3.getStopLoss(),
                        "target",         l3.getTarget1(),
                        "maxHoldDays",    l3.getSuggestedHoldDays(),
                        "entryNote",      l3.getTradeNote(),
                        "swingVerdict",   "BUY",
                        "conviction",     "MEDIUM"
                )));
                return ResponseEntity.ok(Map.of(
                        "status",  "created",
                        "symbol",  symbol,
                        "layer3",  l3,
                        "message", "New signal created with Layer 3 parameters"
                ));
            }

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Batch refinement ──────────────────────────────────────────────────────

    @GetMapping("/scan")
    public ResponseEntity<?> scan(@RequestParam String symbols) {
        if (symbols == null || symbols.isBlank())
            return ResponseEntity.badRequest().body("symbols is required. E.g. ?symbols=HDFCBANK,TITAN");

        List<String> symbolList = List.of(symbols.split(","))
                .stream()
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

        if (symbolList.size() > 10)
            return ResponseEntity.badRequest().body("Maximum 10 symbols per scan");

        List<Layer3Result> results = new java.util.ArrayList<>();
        for (String symbol : symbolList) {
            results.add(layer3Service.refine(symbol, "OTHER"));
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }

        // Sort: ENTER_NOW first, then WAIT_FOR_CONFIRMATION, then AVOID/ERROR
        results.sort((a, b) -> {
            int rankA = signalRank(a.getEntrySignal());
            int rankB = signalRank(b.getEntrySignal());
            if (rankA != rankB) return Integer.compare(rankA, rankB);
            return Double.compare(b.getRrRatio1(), a.getRrRatio1());
        });

        long enterNow   = results.stream().filter(r -> "ENTER_NOW".equals(r.getEntrySignal())).count();
        long waitFor    = results.stream().filter(r -> "WAIT_FOR_CONFIRMATION".equals(r.getEntrySignal())).count();

        return ResponseEntity.ok(Map.of(
                "total",        results.size(),
                "enter_now",    enterNow,
                "wait_confirm", waitFor,
                "results",      results
        ));
    }

    // ── Full pipeline endpoint ─────────────────────────────────────────────────
    //
    // Runs the complete Layer 1 → Layer 2 → Layer 3 pipeline and saves signals.
    // Called from the UI "Run Pipeline" button.
    //
    @GetMapping("/pipeline")
    public ResponseEntity<?> fullPipeline(
            @RequestParam(defaultValue = "NIFTY 50") String index,
            @RequestParam(defaultValue = "10")       int topN
    ) {
        return ResponseEntity.ok(Map.of(
                "message", "Use /api/v1/live/pipeline to run the full pipeline, " +
                           "then call /api/v1/layer3/refine-and-save for each passed symbol"
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int signalRank(String signal) {
        if (signal == null)              return 3;
        return switch (signal) {
            case "ENTER_NOW"             -> 0;
            case "WAIT_FOR_CONFIRMATION" -> 1;
            case "AVOID"                 -> 2;
            default                      -> 3;
        };
    }

}

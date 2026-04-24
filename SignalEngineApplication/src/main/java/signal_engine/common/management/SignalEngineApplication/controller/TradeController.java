package signal_engine.common.management.SignalEngineApplication.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import signal_engine.common.management.SignalEngineApplication.Service.TradeService;
import signal_engine.common.management.SignalEngineApplication.model.Trade;
import signal_engine.common.management.SignalEngineApplication.model.Trade.TradeStatus;

import java.util.List;
import java.util.Map;

/**
 * Trade Tracker REST API
 *
 * Base URL: /api/v1/trades
 *
 * GET    /                          → all trades (all statuses)
 * GET    /?status=SIGNAL            → filter by status
 * GET    /stats                     → win rate, profit factor, P&L summary
 * GET    /:id                       → single trade
 * POST   /                          → create signal manually
 * PATCH  /:id/enter                 → enter a trade (SIGNAL → ACTIVE)
 * PATCH  /:id/price                 → update current market price
 * PATCH  /:id/exit                  → exit trade (ACTIVE → CLOSED)
 * DELETE /:id                       → remove a signal (SIGNAL only)
 */
@RestController
@RequestMapping("/api/v1/trades")
@CrossOrigin(origins = "*")          // allow UI on any port during development
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String status) {
        if (status == null) {
            return ResponseEntity.ok(tradeService.getAll());
        }
        try {
            TradeStatus ts = TradeStatus.valueOf(status.toUpperCase());
            return ResponseEntity.ok(tradeService.getByStatus(ts));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body("Invalid status. Use SIGNAL, ACTIVE, or CLOSED");
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        return ResponseEntity.ok(tradeService.getStats());
    }

    // ── Single trade ──────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            return ResponseEntity.ok(tradeService.getById(id));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Create signal manually ────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> req) {
        if (!req.containsKey("symbol"))
            return ResponseEntity.badRequest().body("symbol is required");
        try {
            return ResponseEntity.ok(tradeService.createSignalManual(req));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── Enter trade ───────────────────────────────────────────────────────────

    @PatchMapping("/{id}/enter")
    public ResponseEntity<?> enter(@PathVariable String id,
                                   @RequestBody Map<String, Object> req) {
        if (!req.containsKey("entryPrice") || !req.containsKey("quantity"))
            return ResponseEntity.badRequest().body("entryPrice and quantity are required");
        try {
            return ResponseEntity.ok(tradeService.enterTrade(id, req));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Update current price ──────────────────────────────────────────────────

    @PatchMapping("/{id}/price")
    public ResponseEntity<?> price(@PathVariable String id,
                                   @RequestBody Map<String, Object> req) {
        if (!req.containsKey("currentPrice"))
            return ResponseEntity.badRequest().body("currentPrice is required");
        try {
            double cmp = Double.parseDouble(req.get("currentPrice").toString());
            return ResponseEntity.ok(tradeService.updatePrice(id, cmp));
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Exit trade ────────────────────────────────────────────────────────────

    @PatchMapping("/{id}/exit")
    public ResponseEntity<?> exit(@PathVariable String id,
                                  @RequestBody Map<String, Object> req) {
        if (!req.containsKey("exitPrice"))
            return ResponseEntity.badRequest().body("exitPrice is required");
        try {
            return ResponseEntity.ok(tradeService.exitTrade(id, req));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Delete signal ─────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            tradeService.deleteSignal(id);
            return ResponseEntity.ok(Map.of("deleted", id));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
    
 // ── Save pipeline results as signals ──────────────────────────────────────
    //
    // Accepts the full pipeline/run response body and saves all passing
    // stocks as SIGNAL records in the trade tracker automatically.
    //
    // POST /api/v1/trades/pipeline
    // Body: the exact JSON returned by GET /api/v1/backtest/pipeline/run
    //
    // Example:
    //   curl -X POST http://localhost:8080/api/v1/trades/pipeline \
    //        -H "Content-Type: application/json" \
    //        -d @pipeline_response.json
    //
    @PostMapping("/pipeline")
    public ResponseEntity<?> saveFromPipeline(@RequestBody Map<String, Object> pipelineResponse) {

        // Navigate to layer2.results
        Object layer2Obj = pipelineResponse.get("layer2");
        if (layer2Obj == null)
            return ResponseEntity.badRequest().body("Missing 'layer2' key in request body");

        @SuppressWarnings("unchecked")
        Map<String, Object> layer2 = (Map<String, Object>) layer2Obj;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) layer2.get("results");
        if (results == null || results.isEmpty())
            return ResponseEntity.badRequest().body("No results found in layer2");

        List<String> saved   = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();
        List<String> errors  = new java.util.ArrayList<>();

        for (Map<String, Object> r : results) {

            // Only save stocks that passed Layer 2
            Boolean passed = (Boolean) r.get("layer2_pass");
            if (passed == null || !passed) {
                skipped.add((String) r.getOrDefault("symbol", "UNKNOWN") + " (rejected)");
                continue;
            }

            try {
                // Map pipeline snake_case keys to TradeService camelCase keys
                Map<String, Object> req = new java.util.LinkedHashMap<>();
                req.put("symbol",         r.get("symbol"));
                req.put("companyName",    r.getOrDefault("company_name", r.get("symbol")));
                req.put("compositeScore", r.get("composite_score"));
                req.put("swingVerdict",   r.get("swing_verdict"));
                req.put("conviction",     r.get("conviction"));
                req.put("rationale",      r.get("rationale"));
                req.put("entryNote",      r.getOrDefault("entry_note", ""));
                req.put("maxHoldDays",    20);

                // Entry zone, stop, target — 0 for now (Layer 3 will refine)
                // These are placeholders; update via PATCH /:id/enter when you trade
                req.put("entryLow",  0.0);
                req.put("entryHigh", 0.0);
                req.put("stopLoss",  0.0);
                req.put("target",    0.0);

                Trade t = tradeService.createSignalManual(req);
                if (t != null) {
                    saved.add((String) r.get("symbol"));
                } else {
                    skipped.add((String) r.get("symbol") + " (already exists)");
                }

            } catch (Exception e) {
                errors.add(r.get("symbol") + ": " + e.getMessage());
            }
        }

        return ResponseEntity.ok(java.util.Map.of(
                "saved",   saved,
                "skipped", skipped,
                "errors",  errors,
                "message", saved.size() + " signal(s) saved to trade tracker"
        ));
    }
}

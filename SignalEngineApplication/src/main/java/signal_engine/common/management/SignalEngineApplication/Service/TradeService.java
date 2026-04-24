package signal_engine.common.management.SignalEngineApplication.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import signal_engine.common.management.SignalEngineApplication.model.Layer2Result;
import signal_engine.common.management.SignalEngineApplication.model.Trade;
import signal_engine.common.management.SignalEngineApplication.model.Trade.TradeStatus;
import signal_engine.common.management.SignalEngineApplication.repository.TradeRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeService {

    private final TradeRepository repo;
    private final MarketDataClient marketDataClient;

    // ── Create signal from Layer 2 result ─────────────────────────────────────

    /**
     * Called automatically by the pipeline when a stock passes Layer 2.
     * Creates a SIGNAL record with all the Layer 2 context attached.
     */
    @Transactional
    public Trade createSignalFromLayer2(Layer2Result layer2, double entryLow,
                                        double entryHigh, double stopLoss, double target) {

        // Don't duplicate — skip if already an active signal or open trade
        if (repo.existsBySymbolAndStatus(layer2.getSymbol(), TradeStatus.SIGNAL) ||
            repo.existsBySymbolAndStatus(layer2.getSymbol(), TradeStatus.ACTIVE)) {
            log.info("[Trade] {} already has an open signal/trade — skipping", layer2.getSymbol());
            return null;
        }

        Trade t = new Trade();
        t.setSymbol(layer2.getSymbol());
        t.setCompanyName(layer2.getCompanyName());
        t.setCompositeScore(layer2.getCompositeScore());
        t.setSwingVerdict(layer2.getSwingVerdict());
        t.setConviction(layer2.getConviction());
        t.setRationale(layer2.getRationale());
        t.setEntryNote(layer2.getEntryNote());
        t.setEntryLow(entryLow);
        t.setEntryHigh(entryHigh);
        t.setStopLoss(stopLoss);
        t.setTarget(target);
        t.setMaxHoldDays(20);
        t.setStatus(TradeStatus.SIGNAL);

        Trade saved = repo.save(t);
        log.info("[Trade] Signal created for {} (score={})", t.getSymbol(), t.getCompositeScore());
        return saved;
    }

    /**
     * Manual signal creation from the UI.
     */
    @Transactional
    public Trade createSignalManual(Map<String, Object> req) {
        Trade t = new Trade();
        t.setSymbol(((String) req.get("symbol")).toUpperCase().trim());
        t.setCompanyName((String) req.getOrDefault("companyName", t.getSymbol()));
        t.setCompositeScore((Integer) req.getOrDefault("compositeScore", 0));
        t.setSwingVerdict((String) req.getOrDefault("swingVerdict", "BUY"));
        t.setConviction((String) req.getOrDefault("conviction", "MEDIUM"));
        t.setRationale((String) req.getOrDefault("rationale", ""));
        t.setEntryNote((String) req.getOrDefault("entryNote", ""));
        t.setEntryLow(toDouble(req.get("entryLow")));
        t.setEntryHigh(toDouble(req.get("entryHigh")));
        t.setStopLoss(toDouble(req.get("stopLoss")));
        t.setTarget(toDouble(req.get("target")));
        t.setMaxHoldDays((Integer) req.getOrDefault("maxHoldDays", 20));
        t.setStatus(TradeStatus.SIGNAL);
        return repo.save(t);
    }

    // ── Enter trade ───────────────────────────────────────────────────────────

    @Transactional
    public Trade enterTrade(String id, Map<String, Object> req) {
        Trade t = findOrThrow(id);
        if (t.getStatus() != TradeStatus.SIGNAL)
            throw new IllegalStateException("Trade is not in SIGNAL state");

        t.setEntryPrice(toDouble(req.get("entryPrice")));
        t.setQuantity((Integer) req.get("quantity"));
        t.setEntryDate(LocalDate.parse((String) req.get("entryDate")));
        t.setCurrentPrice(t.getEntryPrice());
        t.setLastPriceUpdate(LocalDateTime.now());

        // Allow overriding stop/target at entry time
        if (req.containsKey("stopLoss"))  t.setStopLoss(toDouble(req.get("stopLoss")));
        if (req.containsKey("target"))    t.setTarget(toDouble(req.get("target")));
        if (req.containsKey("maxHoldDays")) t.setMaxHoldDays((Integer) req.get("maxHoldDays"));

        t.setStatus(TradeStatus.ACTIVE);
        log.info("[Trade] {} entered at ₹{} x{}", t.getSymbol(), t.getEntryPrice(), t.getQuantity());
        return repo.save(t);
    }

    // ── Update current price ──────────────────────────────────────────────────

    @Transactional
    public Trade updatePrice(String id, double price) {
        Trade t = findOrThrow(id);
        t.setCurrentPrice(price);
        t.setLastPriceUpdate(LocalDateTime.now());

        String alert = t.getExitAlert();
        if (alert != null) {
            log.warn("[Trade] {} exit alert: {}", t.getSymbol(), alert);
        }
        return repo.save(t);
    }

    // ── Exit trade ────────────────────────────────────────────────────────────

    @Transactional
    public Trade exitTrade(String id, Map<String, Object> req) {
        Trade t = findOrThrow(id);
        if (t.getStatus() != TradeStatus.ACTIVE)
            throw new IllegalStateException("Trade is not ACTIVE");

        t.setExitPrice(toDouble(req.get("exitPrice")));
        t.setExitDate(LocalDate.parse((String) req.getOrDefault("exitDate",
                                      LocalDate.now().toString())));
        t.setExitReason((String) req.getOrDefault("exitReason", "MANUAL"));
        t.setStatus(TradeStatus.CLOSED);

        Double pl = t.getPnlPct();
        String plStr = pl != null ? String.format("%.2f", pl) : "N/A";
        log.info("[Trade] {} closed at {} — P&L: {}% ({})",
                 t.getSymbol(), t.getExitPrice(), plStr, t.getExitReason());
        return repo.save(t);
    }

    /**
     * Update an existing SIGNAL record with precise Layer 3 parameters.
     * Called by Layer3Controller after refining entry/stop/target.
     */
    @Transactional
    public Trade updateSignalFromLayer3(String symbol, Map<String, Object> l3) {
        // Find the most recent SIGNAL for this symbol
        List<Trade> signals = repo.findBySymbolOrderByCreatedAtDesc(symbol);
        Trade t = signals.stream()
                .filter(s -> s.getStatus() == TradeStatus.SIGNAL)
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No open SIGNAL found for: " + symbol));

        if (l3.containsKey("entryLow"))    t.setEntryLow(toDouble(l3.get("entryLow")));
        if (l3.containsKey("entryHigh"))   t.setEntryHigh(toDouble(l3.get("entryHigh")));
        if (l3.containsKey("stopLoss"))    t.setStopLoss(toDouble(l3.get("stopLoss")));
        if (l3.containsKey("target"))      t.setTarget(toDouble(l3.get("target")));
        if (l3.containsKey("maxHoldDays")) t.setMaxHoldDays((Integer) l3.get("maxHoldDays"));
        if (l3.containsKey("entryNote"))   t.setEntryNote((String) l3.get("entryNote"));

        log.info("[Trade] {} signal updated with Layer 3 — entry ₹{}–₹{}, stop ₹{}, target ₹{}",
                symbol, t.getEntryLow(), t.getEntryHigh(), t.getStopLoss(), t.getTarget());
        return repo.save(t);
    }

        // ── Delete signal ─────────────────────────────────────────────────────────

    @Transactional
    public void deleteSignal(String id) {
        Trade t = findOrThrow(id);
        if (t.getStatus() != TradeStatus.SIGNAL)
            throw new IllegalStateException("Can only delete SIGNAL records");
        repo.delete(t);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<Trade> getAll()                          { return repo.findAllByOrderByCreatedAtDesc(); }
    public List<Trade> getByStatus(TradeStatus status)   { return repo.findByStatusOrderByCreatedAtDesc(status); }
    public Trade       getById(String id)                { return findOrThrow(id); }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        List<Trade> closed = repo.findByStatusOrderByCreatedAtDesc(TradeStatus.CLOSED);
        if (closed.isEmpty()) return Map.of("message", "No closed trades yet");

        List<Double> pls = closed.stream()
            .filter(t -> t.getEntryPrice() != null && t.getExitPrice() != null)
            .map(t -> ((t.getExitPrice() - t.getEntryPrice()) / t.getEntryPrice()) * 100)
            .collect(Collectors.toList());

        long wins   = pls.stream().filter(p -> p >= 0).count();
        long losses = pls.stream().filter(p -> p < 0).count();

        double avgWin  = pls.stream().filter(p -> p >= 0).mapToDouble(d -> d).average().orElse(0);
        double avgLoss = Math.abs(pls.stream().filter(p -> p < 0).mapToDouble(d -> d).average().orElse(0));

        double grossWin  = pls.stream().filter(p -> p >= 0).mapToDouble(d -> d).sum();
        double grossLoss = Math.abs(pls.stream().filter(p -> p < 0).mapToDouble(d -> d).sum());
        double profitFactor = grossLoss > 0 ? grossWin / grossLoss : 99.99;

        double totalPnlRs = closed.stream()
            .filter(t -> t.getPnlAbs() != null)
            .mapToDouble(Trade::getPnlAbs)
            .sum();

        double avgDays = closed.stream()
            .filter(t -> t.getDaysHeld() != null)
            .mapToInt(Trade::getDaysHeld)
            .average().orElse(0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTrades",   closed.size());
        stats.put("wins",          wins);
        stats.put("losses",        losses);
        stats.put("winRate",       pls.isEmpty() ? 0 : Math.round((double) wins / pls.size() * 100));
        stats.put("avgWinPct",     Math.round(avgWin * 100.0) / 100.0);
        stats.put("avgLossPct",    Math.round(avgLoss * 100.0) / 100.0);
        stats.put("profitFactor",  Math.round(profitFactor * 100.0) / 100.0);
        stats.put("totalPnlRs",    Math.round(totalPnlRs));
        stats.put("avgHoldDays",   Math.round(avgDays * 10.0) / 10.0);
        return stats;
    }

    // ── Scheduled price refresh (every 5 min during market hours) ─────────────

    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshActivePrices() {
        List<Trade> active = repo.findAllActive();
        if (active.isEmpty()) return;

        log.info("[Trade] Refreshing prices for {} active trades", active.size());
        for (Trade t : active) {
            try {
                double cmp = marketDataClient.fetchCurrentPrice(t.getSymbol());
                t.setCurrentPrice(cmp);
                t.setLastPriceUpdate(LocalDateTime.now());
                repo.save(t);

                String alert = t.getExitAlert();
                if (alert != null && !alert.equals("TIME_WARNING")) {
                    log.warn("[Trade] {} — {} alert fired (CMP={})",
                             t.getSymbol(), alert, cmp);
                }
            } catch (Exception e) {
                log.warn("[Trade] Could not refresh price for {}: {}", t.getSymbol(), e.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Trade findOrThrow(String id) {
        return repo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Trade not found: " + id));
    }

    private Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Double) return (Double) v;
        if (v instanceof Integer) return ((Integer) v).doubleValue();
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(v.toString());
    }
}
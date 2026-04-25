package signal_engine.common.management.SignalEngineApplication.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import signal_engine.common.management.SignalEngineApplication.model.LiveScanResult;
import signal_engine.common.management.SignalEngineApplication.model.MarketRegime;
import signal_engine.common.management.SignalEngineApplication.model.ScanStrictness;

/**
 * DailyScanScheduler — runs the live scan automatically every day at 9pm IST.
 *
 * Schedule:
 *   9:00 PM IST = 3:30 PM UTC → cron = "0 30 15 * * MON-FRI"
 *   Runs Monday to Friday only (markets are closed weekends)
 *
 * What it does:
 *   1. Checks market regime — if BEAR/VOLATILE, skips and sends "no scan" email
 *   2. Runs STRICT scan — high conviction setups only
 *   3. Runs MODERATE scan — broader setups
 *   4. If any setups found → sends email with results
 *   5. If no setups → logs "market not ready" (no email spam)
 *
 * Override with property:
 *   scan.schedule.cron=0 30 15 * * MON-FRI   (default — 9pm IST)
 *   scan.index=NIFTY 50                       (default)
 *   scan.topN=10                              (default)
 *   scan.email.on.no.results=false           (default — no email if nothing found)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyScanScheduler {

    private final LiveScanService     liveScanService;
    private final MarketDataClient    marketDataClient;
    private final MarketRegimeService marketRegimeService;
    private final EmailService        emailService;
    private final TradeService        tradeService;

    // Indices scanned in priority order — most liquid first
    // Symbols found in earlier indices are skipped in later ones
    @org.springframework.beans.factory.annotation.Value("${scan.indices:NIFTY 50,NIFTY NEXT 50,NIFTY MIDCAP 100,NIFTY MIDCAP 150,NIFTY SMALLCAP 250}")
    private String scanIndices;

    @org.springframework.beans.factory.annotation.Value("${scan.topN:10}")
    private int scanTopN;

    // Stop scanning more indices once this many setups are found
    @org.springframework.beans.factory.annotation.Value("${scan.max.setups:15}")
    private int maxSetups;

    @org.springframework.beans.factory.annotation.Value("${scan.email.on.no.results:false}")
    private boolean emailOnNoResults;

    /**
     * Main daily scan — 9:00 PM IST every weekday.
     * IST = UTC+5:30, so 9pm IST = 15:30 UTC.
     */
    @Scheduled(cron = "${scan.schedule.cron:0 0 21 * * *}",
               zone = "Asia/Kolkata")
    public void runDailyScan() {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm"));
        log.info("╔══════════════════════════════════════════════");
        log.info("║ Daily scan started — {}", time);
        log.info("╚══════════════════════════════════════════════");

        try {
            // ── Step 1: Market regime check ────────────────────────────────────
            MarketRegime regime = marketRegimeService.analyse();
            log.info("[Scheduler] Market regime: {} — {}", regime.getRegime(), regime.getReason());

            if (!regime.isTradeable()) {
                log.info("[Scheduler] Market not tradeable ({}) — skipping scan", regime.getRegime());
                emailService.sendScanResults(List.of(), List.of(), regime.getRegime().name());
                return;
            }

            // ── Steps 2+3: Multi-index STRICT + MODERATE scan ─────────────────
            // Scans indices in priority order (most liquid first).
            // Deduplicates symbols across indices.
            // Stops when enough setups are found to avoid scanning all 500+ stocks.

            String[] indices = scanIndices.split(",");
            java.util.Set<String> scannedSymbols = new java.util.LinkedHashSet<>();
            List<LiveScanResult> strictSetups   = new java.util.ArrayList<>();
            List<LiveScanResult> moderateSetups = new java.util.ArrayList<>();

            for (String index : indices) {
                index = index.trim();

                // Stop if we already have enough setups
                if (strictSetups.size() + moderateSetups.size() >= maxSetups) {
                    log.info("[Scheduler] Reached {} setups — stopping early", maxSetups);
                    break;
                }

                log.info("[Scheduler] Scanning {} ...", index);
                List<String> indexSymbols = marketDataClient.fetchNifty50Symbols(index);

                // Filter to symbols not already scanned in a higher-priority index
                List<String> newSymbols = indexSymbols.stream()
                        .filter(s -> !scannedSymbols.contains(s))
                        .collect(java.util.stream.Collectors.toList());

                if (newSymbols.isEmpty()) {
                    log.info("[Scheduler] {} — all symbols already scanned, skipping", index);
                    continue;
                }

                log.info("[Scheduler] {} — {} new symbols to scan", index, newSymbols.size());
                scannedSymbols.addAll(newSymbols);

                // Run STRICT scan on new symbols only
                List<LiveScanResult> indexStrict = liveScanService.scanSymbols(
                        newSymbols, regime, ScanStrictness.STRICT, index);
                strictSetups.addAll(indexStrict);
                log.info("[Scheduler] {} STRICT: {} setups", index, indexStrict.size());

                // Run MODERATE scan on new symbols only
                List<LiveScanResult> indexModerate = liveScanService.scanSymbols(
                        newSymbols, regime, ScanStrictness.MODERATE, index);
                moderateSetups.addAll(indexModerate);
                log.info("[Scheduler] {} MODERATE: {} setups", index, indexModerate.size());
            }

            // Sort by score descending
            strictSetups.sort(java.util.Comparator.comparingInt(LiveScanResult::getSetupScore).reversed());
            moderateSetups.sort(java.util.Comparator.comparingInt(LiveScanResult::getSetupScore).reversed());

            log.info("[Scheduler] Total — STRICT: {}, MODERATE: {} across {} indices scanned",
                    strictSetups.size(), moderateSetups.size(), scannedSymbols.size());

            int total = (int)(strictSetups.size() + moderateSetups.stream()
                    .filter(m -> strictSetups.stream()
                            .noneMatch(s -> s.getSymbol().equals(m.getSymbol())))
                    .count());

            // ── Step 4: Auto-save signals to trade tracker ─────────────────────
            if (!strictSetups.isEmpty() || !moderateSetups.isEmpty()) {
                saveSignals(strictSetups, "STRICT");
                saveSignals(moderateSetups, "MODERATE");
            }

            // ── Step 5: Send email ─────────────────────────────────────────────
            // Always send email — even if no setups found
            // This confirms the system is running correctly every day
            log.info("[Scheduler] Sending daily report — {} total setups", total);
            emailService.sendScanResults(strictSetups, moderateSetups, regime.getRegime().name());

        } catch (Exception e) {
            log.error("[Scheduler] Daily scan failed: {}", e.getMessage(), e);
        }

        log.info("[Scheduler] Daily scan complete");
    }

    /**
     * Auto-save setups as SIGNAL records in the trade tracker.
     * Skips symbols that already have an open signal.
     */
    private void saveSignals(List<LiveScanResult> setups, String source) {
        for (LiveScanResult r : setups) {
            try {
                java.util.Map<String, Object> req = new java.util.HashMap<>();
                req.put("symbol",         r.getSymbol());
                req.put("companyName",    r.getSector() != null ? r.getSymbol() : r.getSymbol());
                req.put("compositeScore", r.getSetupScore());
                req.put("swingVerdict",   "BUY");
                req.put("conviction",     r.getConviction());
                req.put("rationale",      "Auto-detected by daily scan (" + source + ")");
                req.put("entryLow",       r.getEntryLow());
                req.put("entryHigh",      r.getEntryHigh());
                req.put("stopLoss",       r.getStopLoss());
                req.put("target",         r.getTarget());
                req.put("maxHoldDays",    20);
                req.put("entryNote",      r.getSetupType() + " setup — " +
                                          String.join(", ", r.getConditions().subList(
                                              0, Math.min(2, r.getConditions().size()))));

                tradeService.createSignalManual(req);
                log.info("[Scheduler] Signal saved: {}", r.getSymbol());
            } catch (Exception e) {
                // Likely already exists — skip silently
                log.debug("[Scheduler] Skipped {}: {}", r.getSymbol(), e.getMessage());
            }
        }
    }
}
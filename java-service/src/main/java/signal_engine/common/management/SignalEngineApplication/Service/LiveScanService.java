package signal_engine.common.management.SignalEngineApplication.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import signal_engine.common.management.SignalEngineApplication.Indicators.ATRIndicator;
import signal_engine.common.management.SignalEngineApplication.Indicators.RSIIndicator;
import signal_engine.common.management.SignalEngineApplication.Indicators.SMAIndicator;
import signal_engine.common.management.SignalEngineApplication.model.Candle;
import signal_engine.common.management.SignalEngineApplication.model.LiveScanResult;
import signal_engine.common.management.SignalEngineApplication.model.MarketRegime;
import signal_engine.common.management.SignalEngineApplication.model.ScanStrictness;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LiveScanService — two-pathway swing trade detection with strict mechanical rules.
 *
 * ════════════════════════════════════════════════════════════════════════
 *  HARD GATES (ALL must pass — any failure = BLOCKED, no scoring)
 * ════════════════════════════════════════════════════════════════════════
 *
 *  Gate 1  Price > SMA50 AND SMA50 slope positive
 *          → Stock must be in an uptrend AND trend must be accelerating
 *
 *  Gate 2  ATR% between 1% and 6%
 *          → Too low (<1%) = dead stock. Too high (>6%) = too risky.
 *
 *  Gate 3  Price structure: last 3 swings show HH + HL
 *          → Uptrend confirmed at the price action level
 *
 *  Gate 4  Risk % ≤ 4% (stop to entry distance)
 *          → Position must be sizeable enough to matter
 *
 *  Gate 5  Market regime not BEAR or VOLATILE
 *          → Never fight the market
 *
 * ════════════════════════════════════════════════════════════════════════
 *  SETUP TYPE DETECTION (after gates pass)
 * ════════════════════════════════════════════════════════════════════════
 *
 *  PULLBACK setup:
 *    - Price within ±2% of SMA20 (pulling back to support)
 *    - RSI between 35–50 (oversold enough to be buyable)
 *    - Volume ≥ 1.5× average (institutional interest)
 *    - NOT within 5% of 52-week high (resistance overhead)
 *
 *  BREAKOUT setup:
 *    - Price above the most recent swing high (breaking out)
 *    - Volume ≥ 2.0× average (breakout MUST have volume confirmation)
 *    - RSI between 55–65 (momentum without being overbought)
 *    - CAN be within 5% of 52-week high (breakout to new highs is valid)
 *
 *  If NEITHER pullback NOR breakout → BLOCKED
 *  Near 52-week high (<5%) → ONLY breakout allowed, pullback REJECTED
 *
 * ════════════════════════════════════════════════════════════════════════
 *  SCORING (0-100, only for stocks that passed all gates + setup type)
 * ════════════════════════════════════════════════════════════════════════
 *
 *  RSI quality      0-30  (how well RSI fits the setup type)
 *  Volume quality   0-30  (how strong the volume confirmation is)
 *  SMA200 filter    0-20  (price above SMA200 = long-term uptrend)
 *  ATR quality      0-10  (tighter ATR = better R:R potential)
 *  RS vs Nifty      0-10  (outperforming = institutional buying)
 *
 * ════════════════════════════════════════════════════════════════════════
 *  SECTOR DEDUP (final step before returning results)
 * ════════════════════════════════════════════════════════════════════════
 *  Only the highest-scoring stock per sector makes it through.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveScanService {

    private final MarketDataClient    marketDataClient;
    private final MarketRegimeService marketRegimeService;

    private static final String NIFTY_PROXY = "NIFTYBEES";

    // ── Configurable thresholds ────────────────────────────────────────────────

    @Value("${live.scan.thread.count:10}")
    private int MAX_THREADS;           // parallel threads for stock analysis

    @Value("${live.scan.data.period:3mo}")
    private String DATA_PERIOD;

    // Gate 1
    @Value("${live.scan.sma50.slope.bars:10}")
    private int SMA50_SLOPE_BARS;

    // Gate 2 — ATR bounds
    @Value("${live.scan.atr.pct.min:1.0}")
    private double ATR_PCT_MIN;

    @Value("${live.scan.atr.pct.max:6.0}")
    private double ATR_PCT_MAX;

    // Gate 3 — Swing structure lookback
    @Value("${live.scan.structure.lookback.bars:20}")
    private int STRUCTURE_LOOKBACK;

    // Gate 4 — Max risk
    @Value("${live.scan.stop.max.pct:4.0}")
    private double STOP_MAX_PCT;

    // Setup detection
    @Value("${live.scan.pullback.band:0.02}")
    private double PULLBACK_BAND;           // ±2% of SMA20

    @Value("${live.scan.resistance.pct:5.0}")
    private double RESISTANCE_PCT;          // within 5% of 52w high = near resistance

    // RSI windows
    @Value("${live.scan.pullback.rsi.low:35}")
    private double PULLBACK_RSI_LOW;

    @Value("${live.scan.pullback.rsi.high:50}")
    private double PULLBACK_RSI_HIGH;

    @Value("${live.scan.breakout.rsi.low:55}")
    private double BREAKOUT_RSI_LOW;

    @Value("${live.scan.breakout.rsi.high:65}")
    private double BREAKOUT_RSI_HIGH;

    // Volume
    @Value("${live.scan.volume.pullback.min:1.5}")
    private double VOL_PULLBACK_MIN;

    @Value("${live.scan.volume.breakout.min:2.0}")
    private double VOL_BREAKOUT_MIN;

    // Stop/target
    @Value("${live.scan.atr.multiplier:2.0}")
    private double ATR_MULTIPLIER;

    @Value("${live.scan.rr.ratio:2.0}")
    private double RR_RATIO;

    // Minimum score to surface as a valid setup
    @Value("${live.scan.setup.min.score:50}")
    private int SETUP_MIN_SCORE;

    // RS lookback
    @Value("${live.scan.rs.lookback.bars:20}")
    private int RS_LOOKBACK_BARS;

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Scan a pre-filtered list of symbols directly.
     * Used by DailyScanScheduler to avoid re-fetching symbols already scanned
     * in a higher-priority index.
     */
    public List<LiveScanResult> scanSymbols(
            List<String> symbols,
            MarketRegime regime,
            ScanStrictness strictness,
            String indexLabel
    ) {
        log.info("[LiveScan] scanSymbols — {} symbols from {}", symbols.size(), indexLabel);

        double niftyReturn = fetchBenchmarkReturn();

        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(
                        Math.min(symbols.size(), MAX_THREADS));

        List<java.util.concurrent.Future<LiveScanResult>> futures = new java.util.ArrayList<>();
        for (String symbol : symbols) {
            futures.add(executor.submit(() -> {
                try {
                    Thread.sleep((long)(Math.random() * 500));
                    return analyseOne(symbol, niftyReturn, regime, strictness);
                } catch (Exception e) {
                    log.warn("[LiveScan] {} error: {}", symbol, e.getMessage());
                    return LiveScanResult.error(symbol, e.getMessage());
                }
            }));
        }

        List<LiveScanResult> results = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<LiveScanResult> future : futures) {
            try {
                results.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                log.warn("[LiveScan] Task failed: {}", e.getMessage());
            }
        }
        executor.shutdown();

        // Return only valid setups, sorted by score
        return results.stream()
                .filter(r -> !r.isError() && r.isSetup())
                .sorted(java.util.Comparator.comparingInt(LiveScanResult::getSetupScore).reversed())
                .collect(java.util.stream.Collectors.toList());
    }

    public List<LiveScanResult> scan(String index, int topN) {
        return scan(index, topN, null, ScanStrictness.MODERATE);
    }

    public List<LiveScanResult> scan(String index, int topN, MarketRegime regime) {
        return scan(index, topN, regime, ScanStrictness.MODERATE);
    }

    public List<LiveScanResult> scan(String index, int topN, MarketRegime regime, ScanStrictness strictness) {
        List<String> symbols = marketDataClient.fetchNifty50Symbols(index);
        log.info("[LiveScan] {} — {} stocks | regime={}", index, symbols.size(),
                regime != null ? regime.getRegime() : "not checked");

        double niftyReturn = fetchBenchmarkReturn();

        // ── Parallel execution ─────────────────────────────────────────────────
        // Uses a thread pool to fetch and analyse multiple stocks concurrently.
        // Thread count is bounded to avoid overwhelming Yahoo Finance with requests.
        int threadCount = Math.min(symbols.size(), MAX_THREADS);
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(threadCount);

        // Submit all analysis tasks
        List<java.util.concurrent.Future<LiveScanResult>> futures = new java.util.ArrayList<>();
        for (String symbol : symbols) {
            final double niftyRet   = niftyReturn;
            final MarketRegime reg  = regime;
            futures.add(executor.submit(() -> {
                try {
                    // Stagger requests slightly to avoid Yahoo Finance rate limiting
                    Thread.sleep((long)(Math.random() * 500));
                    return analyseOne(symbol, niftyRet, reg);
                } catch (Exception e) {
                    log.warn("[LiveScan] {} error: {}", symbol, e.getMessage());
                    return LiveScanResult.error(symbol, e.getMessage());
                }
            }));
        }

        // Collect results
        List<LiveScanResult> results = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<LiveScanResult> future : futures) {
            try {
                results.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("[LiveScan] Task timed out after 30s");
            } catch (Exception e) {
                log.warn("[LiveScan] Task failed: {}", e.getMessage());
            }
        }
        executor.shutdown();

        // Sort: valid setups by score desc → blocked → errors
        results.sort(Comparator
                .<LiveScanResult>comparingInt(r ->
                        r.isError() ? 2 : r.isSetup() ? 0 : 1)
                .thenComparingInt(r -> -r.getSetupScore()));

        // Sector dedup
        List<LiveScanResult> setups = results.stream()
                .filter(r -> !r.isError() && r.isSetup())
                .collect(Collectors.toList());

        Map<String, LiveScanResult> bestPerSector = new LinkedHashMap<>();
        for (LiveScanResult r : setups) {
            String sec = NiftySectorMap.getSector(r.getSymbol());
            if (!bestPerSector.containsKey(sec) ||
                    r.getSetupScore() > bestPerSector.get(sec).getSetupScore()) {
                bestPerSector.put(sec, r);
            }
        }

        List<LiveScanResult> deduped = bestPerSector.values().stream()
                .sorted(Comparator.comparingInt(LiveScanResult::getSetupScore).reversed())
                .collect(Collectors.toList());

        log.info("[LiveScan] {} setups → {} after sector dedup", setups.size(), deduped.size());

        if (topN > 0) {
            return deduped.stream().limit(topN).collect(Collectors.toList());
        }
        return deduped;
    }

    public LiveScanResult analyseOne(String symbol) {
        return analyseOne(symbol, fetchBenchmarkReturn(), null, ScanStrictness.MODERATE);
    }

    public LiveScanResult analyseOne(String symbol, double niftyReturn, MarketRegime regime) {
        return analyseOne(symbol, niftyReturn, regime, ScanStrictness.MODERATE);
    }

    public LiveScanResult analyseOne(String symbol, double niftyReturn, MarketRegime regime, ScanStrictness strictness) {
        List<Candle> candles = marketDataClient.fetchCandles(symbol, DATA_PERIOD, "NS");
        if (candles == null || candles.size() < 55) {
            return LiveScanResult.error(symbol,
                    "Insufficient data (" + (candles == null ? 0 : candles.size()) + " bars)");
        }

        int    last  = candles.size() - 1;
        double price = candles.get(last).getClose();

        // ── Indicators ─────────────────────────────────────────────────────────
        Double rsi    = RSIIndicator.calculate(candles, last, 14);
        Double sma20  = SMAIndicator.calculate(candles, last, 20);
        Double sma50  = SMAIndicator.calculate(candles, last, 50);
        Double sma200 = SMAIndicator.calculate(candles, last, 200);
        Double atr    = ATRIndicator.calculate(candles, last, 14);

        if (rsi == null || sma20 == null || sma50 == null || atr == null) {
            return LiveScanResult.error(symbol, "Indicator warmup incomplete");
        }

        double atrPct = (atr / price) * 100;

        // Volume ratio vs 20-day average
        double avgVol20 = candles.subList(last - 19, last + 1)
                .stream().mapToDouble(Candle::getVolume).average().orElse(0);
        double volumeRatio = avgVol20 > 0 ? candles.get(last).getVolume() / avgVol20 : 0;

        // SMA50 slope
        boolean sma50SlopePositive = isSma50SlopePositive(candles, last);

        // Swing structure
        boolean higherHigh = hasHigherHigh(candles, last, STRUCTURE_LOOKBACK);
        boolean higherLow  = hasHigherLow(candles, last, STRUCTURE_LOOKBACK);

        // Recent high (for breakout detection)
        double recentHigh = candles.subList(Math.max(0, last - STRUCTURE_LOOKBACK), last)
                .stream().mapToDouble(Candle::getHigh).max().orElse(price);

        // 52-week high
        double high52w     = candles.stream().mapToDouble(Candle::getHigh).max().orElse(price);
        double pctTo52wHigh = ((high52w - price) / price) * 100;
        boolean nearResistance = pctTo52wHigh < strictness.resistancePct;

        // Relative strength
        double stockReturn = computeReturn(candles, last, RS_LOOKBACK_BARS);
        boolean outperforms = stockReturn >= niftyReturn;

        // ATR-based stop/target
        double stopLoss  = price - (ATR_MULTIPLIER * atr);
        double target    = price + (ATR_MULTIPLIER * atr * RR_RATIO);
        double riskPct   = ((price - stopLoss) / price) * 100;
        double rewardPct = ((target - price) / price) * 100;
        double rrRatio   = riskPct > 0 ? rewardPct / riskPct : 0;

        String sector = NiftySectorMap.getSector(symbol);

        // ══════════════════════════════════════════════════════════════════════
        // PHASE 1: HARD GATES
        // ══════════════════════════════════════════════════════════════════════
        List<String> gateFailures = new ArrayList<>();

        // Gate 1: Price > SMA50 AND SMA50 slope positive
        if (price <= sma50) {
            gateFailures.add(String.format(
                    "Gate 1: Price (%.2f) ≤ SMA50 (%.2f) — not in uptrend", price, sma50));
        }
        if (strictness.sma50SlopeGate && !sma50SlopePositive) {
            gateFailures.add(String.format(
                    "Gate 1: SMA50 slope flat/negative over last %d bars", SMA50_SLOPE_BARS));
        }

        // Gate 2: ATR bounds
        if (atrPct < strictness.atrPctMin) {
            gateFailures.add(String.format(
                    "Gate 2: ATR %.1f%% too low (min %.0f%%) — stock not moving", atrPct, strictness.atrPctMin));
        }
        if (atrPct > strictness.atrPctMax) {
            gateFailures.add(String.format(
                    "Gate 2: ATR %.1f%% too high (max %.0f%%) — too volatile to size correctly", atrPct, strictness.atrPctMax));
        }

        // Gate 3: HH + HL price structure
        if (strictness.hhHlGate && (!higherHigh || !higherLow)) {
            gateFailures.add(String.format(
                    "Gate 3: Weak structure — %s%s in last %d bars",
                    higherHigh ? "" : "no higher high ",
                    higherLow  ? "" : "no higher low",
                    STRUCTURE_LOOKBACK));
        }

        // Gate 4: Risk % ≤ 4%
        if (riskPct > strictness.stopMaxPct) {
            gateFailures.add(String.format(
                    "Gate 4: Risk %.1f%% > %.0f%% max — stop too wide", riskPct, strictness.stopMaxPct));
        }

        // Gate 5: Market regime
        if (regime != null && !regime.isTradeable()) {
            gateFailures.add("Gate 5: Market regime " + regime.getRegime() + " — " + regime.getReason());
        }

        if (!gateFailures.isEmpty()) {
            return buildResult(symbol, sector, price, rsi, sma20, sma50, sma200, atr, atrPct,
                    volumeRatio, niftyReturn, stockReturn, outperforms,
                    sma50SlopePositive, higherHigh, higherLow, nearResistance,
                    pctTo52wHigh, stopLoss, target, riskPct, rewardPct, rrRatio,
                    0, false, "BLOCKED", "LOW", "BLOCKED", List.of(), gateFailures);
        }

        // ══════════════════════════════════════════════════════════════════════
        // PHASE 2: SETUP TYPE DETECTION
        // ══════════════════════════════════════════════════════════════════════
        boolean isPullback = Math.abs(price - sma20) / sma20 <= strictness.pullbackBand;
        boolean isBreakout = price > recentHigh && volumeRatio >= strictness.volBreakoutMin;

        // No valid setup type
        if (!isPullback && !isBreakout) {
            return buildBlocked(symbol, sector, price, rsi, sma20, sma50, sma200, atr, atrPct,
                    volumeRatio, niftyReturn, stockReturn, outperforms,
                    sma50SlopePositive, higherHigh, higherLow, nearResistance,
                    pctTo52wHigh, stopLoss, target, riskPct, rewardPct, rrRatio,
                    List.of("Neither pullback (not within 2% of SMA20) nor breakout (not above recent high " +
                            String.format("%.2f", recentHigh) + " with 2×volume)"));
        }

        // Near resistance: only breakout is valid
        if (nearResistance && !isBreakout) {
            return buildBlocked(symbol, sector, price, rsi, sma20, sma50, sma200, atr, atrPct,
                    volumeRatio, niftyReturn, stockReturn, outperforms,
                    sma50SlopePositive, higherHigh, higherLow, nearResistance,
                    pctTo52wHigh, stopLoss, target, riskPct, rewardPct, rrRatio,
                    List.of(String.format(
                            "Only %.1f%% below 52-week high — pullback invalid near resistance. " +
                            "Only breakout trades allowed here.", pctTo52wHigh)));
        }

        // Determine final setup type (breakout takes precedence if both qualify)
        String setupType = isBreakout ? "BREAKOUT" : "PULLBACK";

        // ── Setup-specific gates ────────────────────────────────────────────────
        List<String> setupGateFailures = new ArrayList<>();

        if ("PULLBACK".equals(setupType)) {
            // RSI must be in pullback zone (35-50)
            if (rsi < strictness.pullbackRsiLow || rsi > strictness.pullbackRsiHigh) {
                setupGateFailures.add(String.format(
                        "Pullback RSI %.1f not in [%.0f\u2013%.0f] range — " +
                        (rsi < PULLBACK_RSI_LOW ? "wait for more of a pullback" : "RSI too high, momentum fading"),
                        rsi, strictness.pullbackRsiLow, strictness.pullbackRsiHigh));
            }
            // Volume minimum
            if (volumeRatio < strictness.volPullbackMin) {
                setupGateFailures.add(String.format(
                        "Pullback volume %.1fx < %.1fx minimum — no institutional interest", volumeRatio, VOL_PULLBACK_MIN));
            }
        } else { // BREAKOUT
            // RSI must be in breakout zone (55-65)
            if (rsi < strictness.breakoutRsiLow || rsi > strictness.breakoutRsiHigh) {
                setupGateFailures.add(String.format(
                        "Breakout RSI %.1f not in [%.0f–%.0f] range — " +
                        (rsi < BREAKOUT_RSI_LOW ? "RSI too low for a breakout" : "RSI overbought, late entry"),
                        rsi, strictness.breakoutRsiLow, strictness.breakoutRsiHigh));
            }
            // Volume minimum (already checked for breakout type detection, but gate enforces)
            if (volumeRatio < strictness.volBreakoutMin) {
                setupGateFailures.add(String.format(
                        "Breakout volume %.1fx < %.1fx required — false breakout risk", volumeRatio, VOL_BREAKOUT_MIN));
            }
        }

        if (!setupGateFailures.isEmpty()) {
            return buildBlocked(symbol, sector, price, rsi, sma20, sma50, sma200, atr, atrPct,
                    volumeRatio, niftyReturn, stockReturn, outperforms,
                    sma50SlopePositive, higherHigh, higherLow, nearResistance,
                    pctTo52wHigh, stopLoss, target, riskPct, rewardPct, rrRatio,
                    setupGateFailures);
        }

        // ══════════════════════════════════════════════════════════════════════
        // PHASE 3: SCORING (0–100)
        // ══════════════════════════════════════════════════════════════════════
        int    score      = 0;
        List<String> conds = new ArrayList<>();
        List<String> warns = new ArrayList<>();

        // RSI quality (0-30): how well RSI fits the setup type
        if ("PULLBACK".equals(setupType)) {
            // Sweet spot: 38-47 (well into the pullback zone)
            if (rsi >= 38 && rsi <= 47) {
                score += 30;
                conds.add(String.format("RSI %.1f in pullback sweet spot (38–47)", rsi));
            } else {
                score += 15;
                conds.add(String.format("RSI %.1f in pullback zone (35–50)", rsi));
            }
        } else {
            // Sweet spot: 57-63 (momentum with room to run)
            if (rsi >= 57 && rsi <= 63) {
                score += 30;
                conds.add(String.format("RSI %.1f in breakout sweet spot (57–63)", rsi));
            } else {
                score += 15;
                conds.add(String.format("RSI %.1f in breakout zone (55–65)", rsi));
            }
        }

        // Volume quality (0-30)
        double volRequired = "PULLBACK".equals(setupType) ? VOL_PULLBACK_MIN : VOL_BREAKOUT_MIN;
        double volScore    = Math.min(30, (volumeRatio / volRequired - 1.0) * 30 + 15);
        score += (int) Math.max(0, volScore);
        conds.add(String.format("Volume %.1fx avg (%s)", volumeRatio, setupType.equals("BREAKOUT") ? "breakout confirmation" : "institutional interest"));

        // SMA200 (0-20): long-term trend filter
        if (sma200 != null && price > sma200) {
            score += 20;
            conds.add(String.format("Price (%.2f) above SMA200 (%.2f) — long-term uptrend", price, sma200));
        } else if (sma200 == null) {
            score += 10;   // SMA200 not available (3mo data) — neutral
            warns.add("SMA200 not available with 3mo data — regime detection compensates");
        } else {
            warns.add(String.format("Price below SMA200 (%.2f) — counter-trend trade, reduce size", sma200));
        }

        // ATR quality (0-10): tighter ATR = better R:R potential
        if (atrPct <= 2.5) {
            score += 10;
            conds.add(String.format("Tight ATR %.1f%% — excellent R:R", atrPct));
        } else if (atrPct <= 4.0) {
            score += 5;
            conds.add(String.format("Moderate ATR %.1f%%", atrPct));
        }

        // Relative strength (0-10)
        if (outperforms) {
            score += 10;
            conds.add(String.format("Outperforms Nifty by +%.1f%% (20d)", stockReturn - niftyReturn));
        } else {
            warns.add(String.format("Underperforms Nifty by %.1f%% (20d)", niftyReturn - stockReturn));
        }

        // Near resistance warning (not a gate for breakout, but still warn)
        if (nearResistance && "BREAKOUT".equals(setupType)) {
            warns.add(String.format("%.1f%% from 52-week high — breakout to new highs, use tight stop", pctTo52wHigh));
        }

        score = Math.max(0, Math.min(100, score));

        // Verdict
        boolean isSetup;
        String  verdict, conviction;
        if (score >= 70) {
            isSetup = true; verdict = "STRONG " + setupType; conviction = "HIGH";
        } else if (score >= strictness.minSetupScore) {
            isSetup = true; verdict = setupType;              conviction = score >= 60 ? "MEDIUM" : "LOW";
        } else {
            isSetup = false; verdict = "WEAK " + setupType;  conviction = "LOW";
        }

        log.info("[LiveScan] {} → {} | score={} | RSI={} | vol={}x | ATR={}% | {}",
                symbol, verdict, score,
                String.format("%.1f", rsi),
                String.format("%.1f", volumeRatio),
                String.format("%.1f", atrPct),
                setupType);

        return buildResult(symbol, sector, price, rsi, sma20, sma50, sma200, atr, atrPct,
                volumeRatio, niftyReturn, stockReturn, outperforms,
                sma50SlopePositive, higherHigh, higherLow, nearResistance,
                pctTo52wHigh, stopLoss, target, riskPct, rewardPct, rrRatio,
                score, isSetup, verdict, conviction, setupType, conds, warns);
    }

    // ── Technical helpers ──────────────────────────────────────────────────────

    private boolean isSma50SlopePositive(List<Candle> candles, int last) {
        int prev = last - SMA50_SLOPE_BARS;
        if (prev < 50) return false;
        Double now = SMAIndicator.calculate(candles, last, 50);
        Double old = SMAIndicator.calculate(candles, prev, 50);
        return now != null && old != null && now > old;
    }

    private boolean hasHigherHigh(List<Candle> candles, int last, int bars) {
        if (last < bars + 1) return false;
        double prevHigh   = candles.subList(last - bars, last - bars / 2)
                .stream().mapToDouble(Candle::getHigh).max().orElse(0);
        double recentHigh = candles.subList(last - bars / 2, last + 1)
                .stream().mapToDouble(Candle::getHigh).max().orElse(0);
        return recentHigh > prevHigh;
    }

    private boolean hasHigherLow(List<Candle> candles, int last, int bars) {
        if (last < bars + 1) return false;
        double prevLow   = candles.subList(last - bars, last - bars / 2)
                .stream().mapToDouble(Candle::getLow).min().orElse(Double.MAX_VALUE);
        double recentLow = candles.subList(last - bars / 2, last + 1)
                .stream().mapToDouble(Candle::getLow).min().orElse(Double.MAX_VALUE);
        return recentLow > prevLow;
    }

    private double computeReturn(List<Candle> candles, int last, int bars) {
        if (last < bars) return 0;
        double then = candles.get(last - bars).getClose();
        double now  = candles.get(last).getClose();
        return then > 0 ? ((now - then) / then) * 100 : 0;
    }

    private double fetchBenchmarkReturn() {
        try {
            List<Candle> c = marketDataClient.fetchCandles(NIFTY_PROXY, DATA_PERIOD, "NS");
            if (c != null && c.size() > RS_LOOKBACK_BARS)
                return computeReturn(c, c.size() - 1, RS_LOOKBACK_BARS);
        } catch (Exception e) {
            log.warn("[LiveScan] Benchmark fetch failed: {}", e.getMessage());
        }
        return 0;
    }

    // ── Result builders ────────────────────────────────────────────────────────

    private LiveScanResult buildBlocked(
            String symbol, String sector, double price,
            double rsi, double sma20, double sma50, Double sma200,
            double atr, double atrPct, double volumeRatio,
            double niftyReturn, double stockReturn, boolean outperforms,
            boolean sma50Slope, boolean hh, boolean hl, boolean nearRes,
            double pctTo52w, double stop, double target,
            double riskPct, double rewardPct, double rr,
            List<String> reasons) {
        return buildResult(symbol, sector, price, rsi, sma20, sma50, sma200, atr, atrPct,
                volumeRatio, niftyReturn, stockReturn, outperforms,
                sma50Slope, hh, hl, nearRes, pctTo52w, stop, target, riskPct, rewardPct, rr,
                0, false, "BLOCKED", "LOW", "BLOCKED", List.of(), reasons);
    }

    private LiveScanResult buildResult(
            String symbol, String sector, double price,
            double rsi, double sma20, double sma50, Double sma200,
            double atr, double atrPct, double volumeRatio,
            double niftyReturn, double stockReturn, boolean outperforms,
            boolean sma50Slope, boolean hh, boolean hl, boolean nearRes,
            double pctTo52w, double stop, double target,
            double riskPct, double rewardPct, double rr,
            int score, boolean isSetup, String verdict, String conviction, String setupType,
            List<String> conditions, List<String> warnings) {

        return LiveScanResult.builder()
                .symbol(symbol)
                .sector(sector)
                .price(r2(price))
                .rsi(r1(rsi))
                .sma20(r2(sma20))
                .sma50(r2(sma50))
                .sma200(sma200 != null ? r2(sma200) : null)
                .atr(r2(atr))
                .atrPct(r1(atrPct))
                .volumeRatio(r2(volumeRatio))
                .stockReturn20d(r2(stockReturn))
                .niftyReturn20d(r2(niftyReturn))
                .relativeStrength(r2(stockReturn - niftyReturn))
                .sma50SlopePositive(sma50Slope)
                .aboveSma50(price > sma50)
                .aboveSma20(price > sma20)
                .aboveSma200(sma200 != null && price > sma200)
                .higherHigh(hh)
                .higherLow(hl)
                .nearResistance(nearRes)
                .pctTo52wHigh(r2(pctTo52w))
                .setupType(setupType)
                .setupScore(score)
                .isSetup(isSetup)
                .verdict(verdict)
                .conviction(conviction)
                .entryLow(r2(price * 0.99))
                .entryHigh(r2(price * 1.005))
                .stopLoss(r2(stop))
                .target(r2(target))
                .riskPct(r2(riskPct))
                .rewardPct(r2(rewardPct))
                .rrRatio(r2(rr))
                .conditions(conditions)
                .warnings(warnings)
                .error(false)
                .build();
    }

    private double r1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
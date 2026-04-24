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

import java.util.*;
import java.util.stream.Collectors;

/**
 * LiveScanService — maximum selectivity swing trade filter.
 *
 * ═══════════════════════════════════════════════════════════
 *  HARD GATES (all must pass — no score compensation)
 * ═══════════════════════════════════════════════════════════
 *  Gate 1 — Uptrend:           SMA20 > SMA50
 *  Gate 2 — Structural:        Price > SMA50
 *  Gate 3 — RSI range:         20 ≤ RSI ≤ 68 (not crash, not extended)
 *  Gate 4 — Volume minimum:    volume ≥ 0.8× 20d avg (minimum liquidity)
 *  Gate 5 — Stop width:        ATR-based stop ≤ 8% of price
 *  Gate 6 — Relative strength: stock 20d return ≥ Nifty 20d return (outperforming)
 *  Gate 7 — Momentum:          ≥ 5 of last 10 candles closed green
 *
 * ═══════════════════════════════════════════════════════════
 *  SCORING BONUSES (add points but never block a stock)
 * ═══════════════════════════════════════════════════════════
 *  Bonus A — SMA50 slope positive (+10 pts): weekly trend accelerating
 *  Bonus B — Higher high + higher low (+10 pts): clean price structure
 *
 * Rationale: Gates 6 (SMA50 slope) and 8 (price structure) were demoted
 * from hard gates because together they produced 0 results in mixed markets.
 * They still reward better setups through the scoring system.
 *
 * ═══════════════════════════════════════════════════════════
 *  SOFT WARNINGS (don't block, but flag in result)
 * ═══════════════════════════════════════════════════════════
 *  Warn A — Near 52-week high: stock within 8% of 52w high (resistance ahead)
 *
 * ═══════════════════════════════════════════════════════════
 *  SCORING (0-100, only stocks that passed ALL gates)
 * ═══════════════════════════════════════════════════════════
 *  RSI zone          0-40  (sweet spot 30-45 = max; 55-68 = near zero)
 *  Trend quality     0-30  (above SMA20, pullback to SMA20)
 *  Volume quality    0-20  (1.5x+ = strong; <1.0x = penalty)
 *  Stop tightness    0-10  (≤4% = full points)
 *
 * ═══════════════════════════════════════════════════════════
 *  SECTOR DEDUP (final step)
 * ═══════════════════════════════════════════════════════════
 *  Only the highest-scoring stock per sector survives.
 *  Prevents taking 4 IT stocks in the same trade.
 *
 * Expected output: 2-5 stocks → Layer 2 LLM → 2-3 final signals
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveScanService {

    private final MarketDataClient marketDataClient;

    // ── Nifty 50 reference — for relative strength calculation ─────────────────
    // Symbol used to compute the benchmark return for RS filter
    private static final String NIFTY_PROXY = "NIFTYBEES"; // ETF that tracks Nifty 50

    // ── Configurable thresholds ────────────────────────────────────────────────

    @Value("${live.scan.data.period:3mo}")
    private String DATA_PERIOD;

    // Gate 3 — RSI
    @Value("${live.scan.rsi.max:68}")
    private double RSI_MAX;

    @Value("${live.scan.rsi.min:20}")
    private double RSI_MIN;

    // Gate 4 — Volume minimum
    @Value("${live.scan.volume.min.gate:0.8}")
    private double VOLUME_MIN_GATE;

    // Gate 5 — Stop width
    @Value("${live.scan.stop.max.pct:8.0}")
    private double STOP_MAX_PCT;

    // Gate 6 — SMA50 slope lookback
    @Value("${live.scan.sma50.slope.bars:10}")
    private int SMA50_SLOPE_BARS;

    // Gate 7 — Relative strength lookback
    @Value("${live.scan.rs.lookback.bars:20}")
    private int RS_LOOKBACK_BARS;

    // Gate 8 — Price structure lookback
    @Value("${live.scan.structure.lookback.bars:20}")
    private int STRUCTURE_LOOKBACK_BARS;

    // Gate 9 — Momentum candles
    @Value("${live.scan.momentum.bars:10}")
    private int MOMENTUM_BARS;

    @Value("${live.scan.momentum.min.green:5}")
    private int MOMENTUM_MIN_GREEN;

    // Warn A — Near 52w high threshold
    @Value("${live.scan.resistance.pct:8.0}")
    private double RESISTANCE_PCT;

    // Scoring
    @Value("${live.scan.rsi.sweet.low:30}")
    private double RSI_SWEET_LOW;

    @Value("${live.scan.rsi.sweet.high:45}")
    private double RSI_SWEET_HIGH;

    @Value("${live.scan.volume.strong:1.5}")
    private double VOLUME_STRONG;

    @Value("${live.scan.volume.ok:1.2}")
    private double VOLUME_OK;

    @Value("${live.scan.atr.multiplier:2.0}")
    private double ATR_MULTIPLIER;

    @Value("${live.scan.rr.ratio:2.0}")
    private double RR_RATIO;

    @Value("${live.scan.setup.min.score:40}")
    private int SETUP_MIN_SCORE;

    // ── Public entry points ────────────────────────────────────────────────────

    public List<LiveScanResult> scan(String index, int topN) {
        List<String> symbols = marketDataClient.fetchNifty50Symbols(index);
        log.info("[LiveScan] {} — {} stocks", index, symbols.size());

        // Fetch Nifty benchmark return for RS filter
        double niftyReturn = fetchBenchmarkReturn();
        log.info("[LiveScan] Nifty 20d return: {}%", String.format("%.2f", niftyReturn));

        List<LiveScanResult> results = new ArrayList<>();
        int gatePassCount = 0;

        for (String symbol : symbols) {
            try {
                LiveScanResult r = analyseOne(symbol, niftyReturn);
                results.add(r);
                if (!r.isError() && r.isSetup()) gatePassCount++;
                Thread.sleep(200);
            } catch (Exception e) {
                log.warn("[LiveScan] {} error: {}", symbol, e.getMessage());
                results.add(LiveScanResult.error(symbol, e.getMessage()));
            }
        }

        log.info("[LiveScan] Gates passed: {}/{}", gatePassCount, symbols.size());

        // ── Sector deduplication ───────────────────────────────────────────────
        // Among setups, keep only the highest-scoring stock per sector
        List<LiveScanResult> setups = results.stream()
                .filter(r -> !r.isError() && r.isSetup())
                .collect(Collectors.toList());

        Map<String, LiveScanResult> bestPerSector = new LinkedHashMap<>();
        for (LiveScanResult r : setups) {
            String sector = NiftySectorMap.getSector(r.getSymbol());
            if (!bestPerSector.containsKey(sector) ||
                r.getSetupScore() > bestPerSector.get(sector).getSetupScore()) {
                bestPerSector.put(sector, r);
            }
        }

        List<LiveScanResult> deduplicated = bestPerSector.values().stream()
                .sorted(Comparator.comparingInt(LiveScanResult::getSetupScore).reversed())
                .collect(Collectors.toList());

        log.info("[LiveScan] After sector dedup: {} stocks → Layer 2", deduplicated.size());

        if (topN > 0) {
            return deduplicated.stream().limit(topN).collect(Collectors.toList());
        }
        return deduplicated;
    }

    /** Convenience overload — fetches Nifty benchmark internally. Used by single-stock endpoint. */
    public LiveScanResult analyseOne(String symbol) {
        return analyseOne(symbol, fetchBenchmarkReturn());
    }

    public LiveScanResult analyseOne(String symbol, double niftyReturn20d) {
        List<Candle> candles = marketDataClient.fetchCandles(symbol, DATA_PERIOD, "NS");

        if (candles == null || candles.size() < 55) {
            return LiveScanResult.error(symbol,
                    "Insufficient data (" + (candles == null ? 0 : candles.size()) + " bars, need 55+)");
        }

        int    last  = candles.size() - 1;
        double price = candles.get(last).getClose();

        // ── Indicators ────────────────────────────────────────────────────────
        Double rsi   = RSIIndicator.calculate(candles, last, 14);
        Double sma20 = SMAIndicator.calculate(candles, last, 20);
        Double sma50 = SMAIndicator.calculate(candles, last, 50);
        Double atr   = ATRIndicator.calculate(candles, last, 14);

        if (rsi == null || sma20 == null || sma50 == null || atr == null) {
            return LiveScanResult.error(symbol, "Indicator warmup incomplete");
        }

        // ── Derived ───────────────────────────────────────────────────────────
        double avgVolume20 = candles.subList(last - 19, last + 1)
                .stream().mapToDouble(Candle::getVolume).average().orElse(0);
        double volumeRatio = avgVolume20 > 0 ? candles.get(last).getVolume() / avgVolume20 : 0;

        boolean uptrend         = sma20 > sma50;
        boolean aboveSma50      = price > sma50;
        boolean aboveSma20      = price > sma20;
        boolean pullbackToSma20 = price >= sma20 * 0.97 && price <= sma20 * 1.03;

        double stopLoss  = price - (ATR_MULTIPLIER * atr);
        double target    = price + (ATR_MULTIPLIER * atr * RR_RATIO);
        double riskPct   = ((price - stopLoss) / price) * 100;
        double rewardPct = ((target - price) / price) * 100;
        double rrRatio   = riskPct > 0 ? rewardPct / riskPct : 0;

        // Gate 6 — SMA50 slope (last N bars of SMA50 trending up)
        boolean sma50SlopePositive = isSma50SlopePositive(candles, last);

        // Gate 7 — Relative strength vs Nifty
        double stockReturn20d  = computeReturn(candles, last, RS_LOOKBACK_BARS);
        boolean outperformsNifty = stockReturn20d >= niftyReturn20d;

        // Gate 8 — Price structure: higher high + higher low in last N bars
        boolean higherHigh = hasHigherHigh(candles, last, STRUCTURE_LOOKBACK_BARS);
        boolean higherLow  = hasHigherLow(candles, last, STRUCTURE_LOOKBACK_BARS);

        // Gate 9 — Momentum: green candle count
        int greenCandles = countGreenCandles(candles, last, MOMENTUM_BARS);
        boolean momentumOk = greenCandles >= MOMENTUM_MIN_GREEN;

        // Warn A — Near 52-week high
        double high52w = candles.stream().mapToDouble(Candle::getHigh).max().orElse(price);
        double pctTo52wHigh = ((high52w - price) / price) * 100;
        boolean nearResistance = pctTo52wHigh < RESISTANCE_PCT;

        String sector = NiftySectorMap.getSector(symbol);

        // ── Phase 1: Hard gates ───────────────────────────────────────────────
        List<String> gateFailures = new ArrayList<>();

        if (!uptrend)
            gateFailures.add(String.format(
                "Downtrend — SMA20 (%.2f) < SMA50 (%.2f)", sma20, sma50));
        if (!aboveSma50)
            gateFailures.add(String.format(
                "Price (%.2f) below SMA50 (%.2f) — structural weakness", price, sma50));
        if (rsi > RSI_MAX)
            gateFailures.add(String.format(
                "RSI overbought (%.1f > %.0f) — don't chase", rsi, RSI_MAX));
        if (rsi < RSI_MIN)
            gateFailures.add(String.format(
                "RSI crash territory (%.1f < %.0f) — avoid", rsi, RSI_MIN));
        if (volumeRatio < VOLUME_MIN_GATE)
            gateFailures.add(String.format(
                "Volume too thin (%.1fx < %.1fx minimum)", volumeRatio, VOLUME_MIN_GATE));
        if (riskPct > STOP_MAX_PCT)
            gateFailures.add(String.format(
                "Stop too wide (%.1f%% > %.0f%%) — position sizing won't work", riskPct, STOP_MAX_PCT));
        // Gates 6 (SMA50 slope) and 8 (price structure) are now scoring bonuses,
        // not hard gates — too strict when combined, produces 0 results in mixed markets.

        if (!outperformsNifty)
            gateFailures.add(String.format(
                "Underperforming Nifty over %d days (stock %.1f%% vs Nifty %.1f%%)",
                RS_LOOKBACK_BARS, stockReturn20d, niftyReturn20d));
        if (!momentumOk)
            gateFailures.add(String.format(
                "Low momentum — only %d/%d green candles (need %d+)",
                greenCandles, MOMENTUM_BARS, MOMENTUM_MIN_GREEN));

        if (!gateFailures.isEmpty()) {
            return buildBlocked(symbol, sector, price, rsi, sma20, sma50, atr,
                    volumeRatio, uptrend, aboveSma50, aboveSma20, pullbackToSma20,
                    stopLoss, target, riskPct, rewardPct, rrRatio,
                    stockReturn20d, pctTo52wHigh, greenCandles, sma50SlopePositive,
                    outperformsNifty, higherHigh, higherLow, gateFailures);
        }

        // ── Phase 2: Scoring ──────────────────────────────────────────────────
        int score = 0;
        List<String> conditions = new ArrayList<>();
        List<String> warnings   = new ArrayList<>();

        // RSI zone (0-40 pts) — primary timing signal
        if (rsi >= RSI_SWEET_LOW && rsi <= RSI_SWEET_HIGH) {
            score += 40;
            conditions.add(String.format("RSI in sweet spot (%.1f) — oversold but recovering", rsi));
        } else if (rsi < RSI_SWEET_LOW) {
            score += 25;
            conditions.add(String.format("RSI oversold (%.1f)", rsi));
            warnings.add("Deeply oversold — wait for one green close to confirm recovery");
        } else if (rsi <= 55) {
            score += 20;
            conditions.add(String.format("RSI neutral (%.1f)", rsi));
        } else {
            score += 5;
            warnings.add(String.format("RSI elevated (%.1f) — risk of buying late", rsi));
        }

        // Trend quality (0-30 pts)
        if (aboveSma20) {
            score += 15;
            conditions.add("Price above SMA20");
        } else {
            warnings.add("Price below SMA20 — wait for reclaim");
        }
        if (pullbackToSma20) {
            score += 15;
            conditions.add("Pulling back to SMA20 — ideal swing entry zone");
        }

        // Volume quality (0-20 pts)
        if (volumeRatio >= VOLUME_STRONG) {
            score += 20;
            conditions.add(String.format("Strong volume (%.1fx avg) — institutional conviction", volumeRatio));
        } else if (volumeRatio >= VOLUME_OK) {
            score += 10;
            conditions.add(String.format("Volume above average (%.1fx)", volumeRatio));
        } else {
            score -= 5;
            warnings.add(String.format("Weak volume (%.1fx) — wait for confirmation", volumeRatio));
        }

        // Stop quality / R:R (0-10 pts)
        if (riskPct <= 4.0) {
            score += 10;
            conditions.add(String.format("Tight stop (%.1f%%) — excellent R:R of %.1fx", riskPct, rrRatio));
        } else if (riskPct <= 6.0) {
            score += 5;
            conditions.add(String.format("Reasonable stop (%.1f%%) — R:R %.1fx", riskPct, rrRatio));
        }

        // Bonus scoring for demoted gates 6 and 8 (adds points but doesn't block)
        if (sma50SlopePositive) {
            score += 10;
            conditions.add("SMA50 slope positive — weekly trend accelerating");
        } else {
            warnings.add("SMA50 slope flat/declining — trend not strengthening");
        }
        if (higherHigh && higherLow) {
            score += 10;
            conditions.add("Price structure: higher high + higher low confirmed");
        } else {
            warnings.add(String.format("Weak price structure — %s%s",
                higherHigh ? "" : "no higher high ",
                higherLow  ? "" : "no higher low"));
        }

        // Informational (already scored above)
        conditions.add(String.format("Outperforms Nifty by +%.1f%% (20d)", stockReturn20d - niftyReturn20d));
        conditions.add(String.format("Momentum: %d/%d green candles", greenCandles, MOMENTUM_BARS));

        // Warn A — near 52-week high
        if (nearResistance) {
            warnings.add(String.format(
                "Only %.1f%% below 52-week high (%.2f) — potential resistance overhead", pctTo52wHigh, high52w));
        }

        score = Math.max(0, Math.min(100, score));

        // ── Verdict ───────────────────────────────────────────────────────────
        boolean isSetup;
        String  verdict;
        String  conviction;

        if (score >= 70) {
            isSetup = true; verdict = "STRONG SETUP"; conviction = "HIGH";
        } else if (score >= 55) {
            isSetup = true; verdict = "SETUP";         conviction = "MEDIUM";
        } else if (score >= SETUP_MIN_SCORE) {
            isSetup = true; verdict = "WEAK SETUP";    conviction = "LOW";
        } else {
            isSetup = false; verdict = "NO SETUP";     conviction = "LOW";
        }

        log.info("[LiveScan] {} → {} | score={} | RSI={} | vol={}x | RS=+{}% | sector={}",
                symbol, verdict, score,
                String.format("%.1f", rsi),
                String.format("%.1f", volumeRatio),
                String.format("%.1f", stockReturn20d - niftyReturn20d),
                sector);

        return LiveScanResult.builder()
                .symbol(symbol)
                .sector(sector)
                .price(r2(price))
                .rsi(r1(rsi))
                .sma20(r2(sma20))
                .sma50(r2(sma50))
                .atr(r2(atr))
                .volumeRatio(r2(volumeRatio))
                .stockReturn20d(r2(stockReturn20d))
                .niftyReturn20d(r2(niftyReturn20d))
                .relativeStrength(r2(stockReturn20d - niftyReturn20d))
                .sma50SlopePositive(sma50SlopePositive)
                .uptrend(uptrend)
                .aboveSma50(aboveSma50)
                .aboveSma20(aboveSma20)
                .pullbackToSma20(pullbackToSma20)
                .higherHigh(higherHigh)
                .higherLow(higherLow)
                .greenCandles(greenCandles)
                .pctTo52wHigh(r2(pctTo52wHigh))
                .nearResistance(nearResistance)
                .setupScore(score)
                .isSetup(isSetup)
                .verdict(verdict)
                .conviction(conviction)
                .entryLow(r2(price * 0.99))
                .entryHigh(r2(price * 1.005))
                .stopLoss(r2(stopLoss))
                .target(r2(target))
                .riskPct(r2(riskPct))
                .rewardPct(r2(rewardPct))
                .rrRatio(r2(rrRatio))
                .conditions(conditions)
                .warnings(warnings)
                .error(false)
                .build();
    }

    // ── Technical helpers ─────────────────────────────────────────────────────

    /**
     * Gate 6 — SMA50 slope positive.
     * Computes SMA50 N bars ago and checks it's lower than today's SMA50.
     * Avoids using static SMA — instead uses price average of last 50 bars
     * at two points in time to derive slope direction.
     */
    private boolean isSma50SlopePositive(List<Candle> candles, int last) {
        int prev = last - SMA50_SLOPE_BARS;
        if (prev < 50) return false;
        Double sma50Now  = SMAIndicator.calculate(candles, last, 50);
        Double sma50Prev = SMAIndicator.calculate(candles, prev, 50);
        if (sma50Now == null || sma50Prev == null) return false;
        return sma50Now > sma50Prev;
    }

    /**
     * Gate 7 — Stock return over N bars.
     * Used to compare against Nifty benchmark return.
     */
    private double computeReturn(List<Candle> candles, int last, int bars) {
        if (last < bars) return 0;
        double then = candles.get(last - bars).getClose();
        double now  = candles.get(last).getClose();
        return then > 0 ? ((now - then) / then) * 100 : 0;
    }

    /**
     * Gate 8 — Higher high.
     * Checks if the current bar's high is above the highest high of the
     * prior N bars (excluding the current bar).
     */
    private boolean hasHigherHigh(List<Candle> candles, int last, int bars) {
        if (last < bars + 1) return false;
        double prevHigh = candles.subList(last - bars, last).stream()
                .mapToDouble(Candle::getHigh).max().orElse(Double.MAX_VALUE);
        // Recent high within last 3 bars must exceed previous period's high
        double recentHigh = candles.subList(Math.max(0, last - 3), last + 1).stream()
                .mapToDouble(Candle::getHigh).max().orElse(0);
        return recentHigh > prevHigh;
    }

    /**
     * Gate 8 — Higher low.
     * Checks if the most recent significant low is above the prior period's low.
     */
    private boolean hasHigherLow(List<Candle> candles, int last, int bars) {
        if (last < bars + 1) return false;
        double prevLow = candles.subList(last - bars, last - bars / 2).stream()
                .mapToDouble(Candle::getLow).min().orElse(0);
        double recentLow = candles.subList(last - bars / 2, last + 1).stream()
                .mapToDouble(Candle::getLow).min().orElse(Double.MAX_VALUE);
        return recentLow > prevLow;
    }

    /**
     * Gate 9 — Count green candles (close > open) in last N bars.
     */
    private int countGreenCandles(List<Candle> candles, int last, int bars) {
        int start = Math.max(0, last - bars + 1);
        return (int) candles.subList(start, last + 1).stream()
                .filter(c -> c.getClose() > c.getOpen())
                .count();
    }

    /**
     * Fetch the Nifty 50 benchmark 20-day return using NIFTYBEES ETF.
     * Falls back to 0 if unavailable (stock still needs RS ≥ 0).
     */
    private double fetchBenchmarkReturn() {
        try {
            List<Candle> nifty = marketDataClient.fetchCandles(NIFTY_PROXY, DATA_PERIOD, "NS");
            if (nifty != null && nifty.size() >= RS_LOOKBACK_BARS + 1) {
                int last = nifty.size() - 1;
                return computeReturn(nifty, last, RS_LOOKBACK_BARS);
            }
        } catch (Exception e) {
            log.warn("[LiveScan] Could not fetch Nifty benchmark ({}), using 0%", e.getMessage());
        }
        return 0.0;
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private LiveScanResult buildBlocked(
            String symbol, String sector, double price,
            double rsi, double sma20, double sma50, double atr,
            double volumeRatio, boolean uptrend, boolean aboveSma50,
            boolean aboveSma20, boolean pullbackToSma20,
            double stopLoss, double target, double riskPct,
            double rewardPct, double rrRatio,
            double stockReturn, double pctTo52wHigh,
            int greenCandles, boolean sma50Slope,
            boolean outperforms, boolean hh, boolean hl,
            List<String> gateFailures) {

        return LiveScanResult.builder()
                .symbol(symbol)
                .sector(sector)
                .price(r2(price))
                .rsi(r1(rsi))
                .sma20(r2(sma20))
                .sma50(r2(sma50))
                .atr(r2(atr))
                .volumeRatio(r2(volumeRatio))
                .uptrend(uptrend)
                .aboveSma50(aboveSma50)
                .aboveSma20(aboveSma20)
                .pullbackToSma20(pullbackToSma20)
                .sma50SlopePositive(sma50Slope)
                .higherHigh(hh)
                .higherLow(hl)
                .greenCandles(greenCandles)
                .pctTo52wHigh(r2(pctTo52wHigh))
                .stopLoss(r2(stopLoss))
                .target(r2(target))
                .riskPct(r2(riskPct))
                .rewardPct(r2(rewardPct))
                .rrRatio(r2(rrRatio))
                .setupScore(0)
                .isSetup(false)
                .verdict("BLOCKED")
                .conviction("LOW")
                .conditions(List.of())
                .warnings(gateFailures)
                .error(false)
                .build();
    }

    private double r1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
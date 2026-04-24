package signal_engine.common.management.SignalEngineApplication.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import signal_engine.common.management.SignalEngineApplication.Indicators.ATRIndicator;
import signal_engine.common.management.SignalEngineApplication.model.Candle;
import signal_engine.common.management.SignalEngineApplication.model.Layer3Result;
import signal_engine.common.management.SignalEngineApplication.model.Layer3Result.SRLevel;
import signal_engine.common.management.SignalEngineApplication.model.LiveScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Layer 3 — Precise trade parameter refinement.
 *
 * Takes a stock that passed Layer 1 (indicator gates) + Layer 2 (LLM analysis)
 * and computes exact entry, stop loss, and targets from actual price structure.
 *
 * ── What it does ──────────────────────────────────────────────────────────────
 *
 * 1. SUPPORT/RESISTANCE DETECTION
 *    Finds swing pivots in the last 60 bars using a pivot-point algorithm.
 *    Clusters nearby levels (within 1.5% of each other) to avoid noise.
 *    Assigns "strength" = number of times the level was tested.
 *
 * 2. PRECISE STOP LOSS
 *    Places stop below the nearest significant support level + 0.5×ATR buffer.
 *    Falls back to entry − 2×ATR if no suitable support exists within 7%.
 *
 * 3. DUAL TARGETS
 *    Target 1: nearest resistance level above price (first exit).
 *    Target 2: next resistance or ATR projection (trailing/stretch target).
 *
 * 4. CANDLESTICK CONFIRMATION
 *    Checks the last 3 bars for bullish reversal/continuation patterns.
 *    Returns ENTER_NOW, WAIT_FOR_CONFIRMATION, or AVOID.
 *
 * 5. ENTRY TIMING
 *    NOW        — strong candle signal at or near support
 *    ON_PULLBACK — price extended above entry zone, wait for dip
 *    ON_BREAKOUT — price consolidating, enter on volume breakout
 *
 * ── Data required ─────────────────────────────────────────────────────────────
 * Fetches 6 months of daily candles (≈125 bars) — enough to find
 * meaningful swing points and compute ATR accurately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Layer3Service {

    private final MarketDataClient marketDataClient;

    // ── Tunable thresholds ─────────────────────────────────────────────────────

    @Value("${layer3.data.period:6mo}")
    private String DATA_PERIOD;

    @Value("${layer3.pivot.bars:3}")
    private int PIVOT_BARS;           // bars each side to confirm a swing pivot

    @Value("${layer3.sr.lookback:60}")
    private int SR_LOOKBACK;          // bars back to search for S/R levels

    @Value("${layer3.cluster.tolerance:0.015}")
    private double CLUSTER_TOLERANCE; // levels within 1.5% are the same zone

    @Value("${layer3.stop.atr.multiplier:2.0}")
    private double STOP_ATR;          // fallback stop multiplier

    @Value("${layer3.stop.buffer.atr:0.5}")
    private double STOP_BUFFER_ATR;   // extra buffer below support level

    @Value("${layer3.stop.max.pct:7.0}")
    private double STOP_MAX_PCT;      // reject support levels that create >7% stop

    @Value("${layer3.stop.min.atr:1.0}")
    private double STOP_MIN_ATR;      // support must be at least 1 ATR below price

    @Value("${layer3.target.atr.multiplier:4.0}")
    private double TARGET_ATR;        // ATR projection multiplier for target2 fallback

    @Value("${layer3.min.rr.ratio:1.5}")
    private double MIN_RR_RATIO;      // minimum R:R for a valid trade

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Refine a passed Layer 2 stock into a precise trade plan.
     *
     * @param liveScan  The LiveScanResult that passed all gates
     */
    public Layer3Result refine(LiveScanResult liveScan) {
        return refine(liveScan.getSymbol(), liveScan.getSector());
    }

    /**
     * Refine by symbol directly (used when calling from controller).
     */
    public Layer3Result refine(String symbol, String sector) {
        log.info("[Layer3] Refining {} ...", symbol);
        try {
            List<Candle> candles = marketDataClient.fetchCandles(symbol, DATA_PERIOD, "NS");
            if (candles == null || candles.size() < 30) {
                return error(symbol, "Insufficient candle data (" +
                        (candles == null ? 0 : candles.size()) + " bars)");
            }
            return compute(symbol, sector, candles);
        } catch (Exception e) {
            log.warn("[Layer3] {} failed: {}", symbol, e.getMessage());
            return error(symbol, e.getMessage());
        }
    }

    // ── Core computation ───────────────────────────────────────────────────────

    private Layer3Result compute(String symbol, String sector, List<Candle> candles) {
        int last = candles.size() - 1;
        Candle today = candles.get(last);
        double price = today.getClose();

        // ATR
        Double atrVal = ATRIndicator.calculate(candles, last, 14);
        double atr    = (atrVal != null) ? atrVal : price * 0.025;

        // ── Step 1: Find swing pivots ─────────────────────────────────────────
        List<int[]> rawLows  = findSwingLows(candles, last);
        List<int[]> rawHighs = findSwingHighs(candles, last);

        // ── Step 2: Cluster nearby levels ─────────────────────────────────────
        List<SRLevel> supports    = clusterToLevels(rawLows,  "SUPPORT",    last);
        List<SRLevel> resistances = clusterToLevels(rawHighs, "RESISTANCE", last);

        // ── Step 3: Filter to relevant levels ─────────────────────────────────
        List<SRLevel> supportsBelow = supports.stream()
                .filter(s -> s.getPrice() < price)
                .sorted((a, b) -> Double.compare(b.getPrice(), a.getPrice())) // closest first
                .limit(3)
                .collect(Collectors.toList());

        List<SRLevel> resistsAbove = resistances.stream()
                .filter(r -> r.getPrice() > price)
                .sorted((a, b) -> Double.compare(a.getPrice(), b.getPrice())) // closest first
                .limit(3)
                .collect(Collectors.toList());

        // ── Step 4: 52-week high ──────────────────────────────────────────────
        double high52w     = candles.subList(Math.max(0, last - 252), last + 1)
                               .stream().mapToDouble(Candle::getHigh).max().orElse(price);
        double pctTo52wHigh = ((high52w - price) / price) * 100;

        // ── Step 5: Precise stop loss ─────────────────────────────────────────
        StopResult stopResult = computeStop(supportsBelow, price, atr);

        // ── Step 6: Entry zone ────────────────────────────────────────────────
        double entryLow  = computeEntryLow(supportsBelow, price, atr);
        double entryHigh = price * 1.005;  // just above current for breakout entry

        // Recalculate risk from entry
        double riskPct = entryLow > stopResult.stop
                ? ((entryLow - stopResult.stop) / entryLow) * 100
                : atr / price * 200;

        // ── Step 7: Targets ───────────────────────────────────────────────────
        TargetResult targets = computeTargets(resistsAbove, price, atr, high52w, pctTo52wHigh);

        double rewardPct1 = ((targets.target1 - entryLow) / entryLow) * 100;
        double rewardPct2 = ((targets.target2 - entryLow) / entryLow) * 100;
        double rrRatio1   = riskPct > 0 ? rewardPct1 / riskPct : 0;
        double rrRatio2   = riskPct > 0 ? rewardPct2 / riskPct : 0;

        // Estimated days to target 1 based on ATR daily velocity
        double dailyPctMove    = (atr / price) * 100;
        int    suggestedDays   = dailyPctMove > 0
                ? (int) Math.ceil(rewardPct1 / dailyPctMove) + 3
                : 20;
        suggestedDays = Math.min(Math.max(suggestedDays, 7), 30);

        // ── Step 8: Candlestick patterns ──────────────────────────────────────
        CandlePattern pattern = detectPattern(candles, last);

        // ── Step 9: At-support check ──────────────────────────────────────────
        boolean atSupport = supportsBelow.stream()
                .anyMatch(s -> Math.abs(s.getPrice() - price) / price < 0.02);

        // ── Step 10: Entry timing ─────────────────────────────────────────────
        String entrySignal;
        String entryTiming;

        if (rrRatio1 < MIN_RR_RATIO) {
            entrySignal = "AVOID";
            entryTiming = "R:R too poor — wait for better entry";
        } else if (pattern.isBullish && atSupport) {
            entrySignal = "ENTER_NOW";
            entryTiming = "NOW — bullish pattern at support";
        } else if (atSupport) {
            entrySignal = "WAIT_FOR_CONFIRMATION";
            entryTiming = "ON_PULLBACK — at support, wait for green close";
        } else if (price < entryHigh * 1.01) {
            entrySignal = "WAIT_FOR_CONFIRMATION";
            entryTiming = "ON_BREAKOUT — wait for volume expansion above " +
                          String.format("%.2f", entryHigh);
        } else {
            entrySignal = "WAIT_FOR_CONFIRMATION";
            entryTiming = "ON_PULLBACK — price extended, wait for dip to " +
                          String.format("%.2f–%.2f", entryLow, entryHigh);
        }

        // ── Step 11: Trade note ───────────────────────────────────────────────
        String tradeNote = buildTradeNote(symbol, entryLow, entryHigh,
                stopResult.stop, targets.target1, rrRatio1, pattern, atSupport);

        log.info("[Layer3] {} → entry ₹{}–₹{} | stop ₹{} ({:.1f}%) | T1 ₹{} R:R {:.1f}x | {}",
                symbol,
                String.format("%.2f", entryLow),
                String.format("%.2f", entryHigh),
                String.format("%.2f", stopResult.stop),
                riskPct,
                String.format("%.2f", targets.target1),
                rrRatio1,
                entrySignal);

        return Layer3Result.builder()
                .symbol(symbol)
                .sector(sector)
                .currentPrice(r2(price))
                .atr(r2(atr))
                .supportLevels(supportsBelow)
                .resistanceLevels(resistsAbove)
                .nearest52wHigh(r2(high52w))
                .pctTo52wHigh(r2(pctTo52wHigh))
                .entryLow(r2(entryLow))
                .entryHigh(r2(entryHigh))
                .stopLoss(r2(stopResult.stop))
                .stopMethod(stopResult.method)
                .stopLevel(stopResult.level != null ? r2(stopResult.level) : null)
                .target1(r2(targets.target1))
                .target2(r2(targets.target2))
                .target1Method(targets.method1)
                .target2Method(targets.method2)
                .riskPct(r2(riskPct))
                .rewardPct1(r2(rewardPct1))
                .rewardPct2(r2(rewardPct2))
                .rrRatio1(r2(rrRatio1))
                .rrRatio2(r2(rrRatio2))
                .suggestedHoldDays(suggestedDays)
                .entrySignal(entrySignal)
                .candlePattern(pattern.name)
                .atSupport(atSupport)
                .entryTiming(entryTiming)
                .tradeNote(tradeNote)
                .error(false)
                .build();
    }

    // ── Swing pivot detection ──────────────────────────────────────────────────

    /**
     * Returns [index, price*100] for swing lows in the lookback window.
     * A pivot low: its low is lower than PIVOT_BARS candles on each side.
     */
    private List<int[]> findSwingLows(List<Candle> candles, int last) {
        List<int[]> pivots = new ArrayList<>();
        int start = Math.max(PIVOT_BARS, last - SR_LOOKBACK);
        for (int i = start; i <= last - PIVOT_BARS; i++) {
            double low = candles.get(i).getLow();
            boolean isPivot = true;
            for (int j = 1; j <= PIVOT_BARS && isPivot; j++) {
                if (candles.get(i - j).getLow() < low ||
                    candles.get(i + j).getLow() < low) isPivot = false;
            }
            if (isPivot) pivots.add(new int[]{i, (int)(low * 100)});
        }
        return pivots;
    }

    /**
     * Returns [index, price*100] for swing highs in the lookback window.
     */
    private List<int[]> findSwingHighs(List<Candle> candles, int last) {
        List<int[]> pivots = new ArrayList<>();
        int start = Math.max(PIVOT_BARS, last - SR_LOOKBACK);
        for (int i = start; i <= last - PIVOT_BARS; i++) {
            double high = candles.get(i).getHigh();
            boolean isPivot = true;
            for (int j = 1; j <= PIVOT_BARS && isPivot; j++) {
                if (candles.get(i - j).getHigh() > high ||
                    candles.get(i + j).getHigh() > high) isPivot = false;
            }
            if (isPivot) pivots.add(new int[]{i, (int)(high * 100)});
        }
        return pivots;
    }

    /**
     * Cluster raw pivot points into S/R levels.
     * Nearby pivots (within CLUSTER_TOLERANCE%) are merged into one level.
     */
    private List<SRLevel> clusterToLevels(List<int[]> pivots, String type, int last) {
        if (pivots.isEmpty()) return List.of();

        List<SRLevel> levels = new ArrayList<>();
        boolean[]     used   = new boolean[pivots.size()];

        for (int i = 0; i < pivots.size(); i++) {
            if (used[i]) continue;
            double basePrice = pivots.get(i)[1] / 100.0;
            int    baseIdx   = pivots.get(i)[0];

            List<int[]> cluster = new ArrayList<>();
            cluster.add(pivots.get(i));

            for (int j = i + 1; j < pivots.size(); j++) {
                if (used[j]) continue;
                double price = pivots.get(j)[1] / 100.0;
                if (Math.abs(price - basePrice) / basePrice < CLUSTER_TOLERANCE) {
                    cluster.add(pivots.get(j));
                    used[j] = true;
                }
            }
            used[i] = true;

            double avgPrice = cluster.stream().mapToDouble(p -> p[1] / 100.0).average().orElse(basePrice);
            int    mostRecent = cluster.stream().mapToInt(p -> p[0]).max().orElse(baseIdx);

            levels.add(SRLevel.builder()
                    .price(r2(avgPrice))
                    .strength(cluster.size())
                    .barsAgo(last - mostRecent)
                    .type(type)
                    .build());
        }

        // Sort by strength descending, then recency
        levels.sort((a, b) -> {
            int cmp = Integer.compare(b.getStrength(), a.getStrength());
            return cmp != 0 ? cmp : Integer.compare(a.getBarsAgo(), b.getBarsAgo());
        });

        return levels;
    }

    // ── Stop loss computation ──────────────────────────────────────────────────

    private record StopResult(double stop, String method, Double level) {}

    private StopResult computeStop(List<SRLevel> supportsBelow, double price, double atr) {
        for (SRLevel s : supportsBelow) {
            double distAtr = (price - s.getPrice()) / atr;
            double stopWithBuffer = s.getPrice() - (STOP_BUFFER_ATR * atr);
            double stopPct = ((price - stopWithBuffer) / price) * 100;

            if (distAtr >= STOP_MIN_ATR && stopPct <= STOP_MAX_PCT) {
                return new StopResult(stopWithBuffer, "SUPPORT_BASED", s.getPrice());
            }
        }
        // Fallback: ATR-based
        return new StopResult(price - (STOP_ATR * atr), "ATR_BASED", null);
    }

    // ── Entry zone computation ─────────────────────────────────────────────────

    private double computeEntryLow(List<SRLevel> supportsBelow, double price, double atr) {
        // If nearest support is very close (within 1.5%), use it as entry low
        if (!supportsBelow.isEmpty()) {
            double nearest = supportsBelow.get(0).getPrice();
            double dist    = (price - nearest) / price;
            if (dist < 0.015) return nearest * 1.002; // just above support
        }
        return price * 0.99;  // default 1% pullback entry
    }

    // ── Target computation ─────────────────────────────────────────────────────

    private record TargetResult(double target1, String method1,
                                 double target2, String method2) {}

    private TargetResult computeTargets(List<SRLevel> resistsAbove, double price,
                                         double atr, double high52w, double pctTo52wHigh) {
        double target1, target2;
        String method1, method2;

        // Target 1: nearest resistance
        if (!resistsAbove.isEmpty()) {
            target1 = resistsAbove.get(0).getPrice();
            method1 = "RESISTANCE_BASED";
        } else {
            target1 = price + (TARGET_ATR * atr);
            method1 = "ATR_PROJECTION";
        }

        // Target 2: next resistance or 52w high if within 15%
        if (resistsAbove.size() >= 2) {
            target2 = resistsAbove.get(1).getPrice();
            method2 = "RESISTANCE_BASED";
        } else if (pctTo52wHigh > 0 && pctTo52wHigh < 15) {
            target2 = high52w;
            method2 = "52W_HIGH";
        } else {
            target2 = price + (TARGET_ATR * 1.5 * atr);
            method2 = "ATR_PROJECTION";
        }

        return new TargetResult(target1, method1, target2, method2);
    }

    // ── Candlestick pattern detection ──────────────────────────────────────────

    private record CandlePattern(String name, boolean isBullish) {}

    private CandlePattern detectPattern(List<Candle> candles, int last) {
        if (last < 2) return new CandlePattern("NONE", false);

        Candle c0 = candles.get(last);     // today
        Candle c1 = candles.get(last - 1); // yesterday
        Candle c2 = candles.get(last - 2); // day before

        double body0   = Math.abs(c0.getClose() - c0.getOpen());
        double body1   = Math.abs(c1.getClose() - c1.getOpen());
        double range0  = c0.getHigh() - c0.getLow();
        double lowerWick0 = Math.min(c0.getOpen(), c0.getClose()) - c0.getLow();
        double upperWick0 = c0.getHigh() - Math.max(c0.getOpen(), c0.getClose());

        // Hammer: small body in upper third, lower wick > 2× body
        boolean isHammer = c0.getClose() > c0.getOpen()
                && lowerWick0 > 2.0 * body0
                && upperWick0 < body0 * 0.5
                && body0 < range0 * 0.35;

        if (isHammer) return new CandlePattern("HAMMER", true);

        // Bullish engulfing: today's green body > yesterday's red body
        boolean isBullishEngulf = c0.getClose() > c0.getOpen()    // today green
                && c1.getClose() < c1.getOpen()                   // yesterday red
                && c0.getClose() > c1.getOpen()                   // today close > yesterday open
                && c0.getOpen()  < c1.getClose();                 // today open < yesterday close

        if (isBullishEngulf) return new CandlePattern("BULLISH_ENGULFING", true);

        // Morning star: red → small body (doji) → green
        boolean isMorningStar = c2.getClose() < c2.getOpen()      // day -2 red
                && Math.abs(c1.getClose() - c1.getOpen()) < (c1.getHigh() - c1.getLow()) * 0.25 // doji
                && c0.getClose() > c0.getOpen()                   // today green
                && c0.getClose() > (c2.getOpen() + c2.getClose()) / 2; // closes above midpoint of c2

        if (isMorningStar) return new CandlePattern("MORNING_STAR", true);

        // Pin bar: long lower wick rejection (like hammer but can be red)
        boolean isPinBar = lowerWick0 > 2.5 * body0
                && lowerWick0 > range0 * 0.6;

        if (isPinBar) return new CandlePattern("PIN_BAR", true);

        // Inside bar: today's range inside yesterday's range (consolidation)
        boolean isInsideBar = c0.getHigh() < c1.getHigh()
                && c0.getLow() > c1.getLow();

        if (isInsideBar) return new CandlePattern("INSIDE_BAR", false); // neutral, not bullish

        return new CandlePattern("NONE", false);
    }

    // ── Trade note builder ─────────────────────────────────────────────────────

    private String buildTradeNote(String symbol, double entryLow, double entryHigh,
                                   double stop, double target1, double rrRatio,
                                   CandlePattern pattern, boolean atSupport) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Enter %s between ₹%.2f–%.2f. ", symbol, entryLow, entryHigh));
        sb.append(String.format("Stop below ₹%.2f. ", stop));
        sb.append(String.format("Target ₹%.2f (R:R %.1fx). ", target1, rrRatio));
        if (atSupport && pattern.isBullish) {
            sb.append(String.format("%s pattern at support — high-probability setup.", pattern.name));
        } else if (atSupport) {
            sb.append("At support — wait for a green close before entering.");
        } else {
            sb.append("Wait for pullback to entry zone before entering.");
        }
        return sb.toString();
    }

    // ── Error factory ──────────────────────────────────────────────────────────

    private Layer3Result error(String symbol, String message) {
        return Layer3Result.builder()
                .symbol(symbol)
                .error(true)
                .errorMessage(message)
                .entrySignal("AVOID")
                .build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}

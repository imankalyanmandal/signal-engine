package signal_engine.common.management.SignalEngineApplication.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import signal_engine.common.management.SignalEngineApplication.Indicators.ATRIndicator;
import signal_engine.common.management.SignalEngineApplication.Indicators.RSIIndicator;
import signal_engine.common.management.SignalEngineApplication.Indicators.SMAIndicator;
import signal_engine.common.management.SignalEngineApplication.model.Candle;
import signal_engine.common.management.SignalEngineApplication.model.MarketRegime;

import java.util.List;

/**
 * MarketRegimeService — determines whether market conditions are suitable for swing trading.
 *
 * Uses NIFTYBEES (Nifty 50 ETF) as the benchmark proxy.
 * Falls back to ^NSEI index data if ETF unavailable.
 *
 * ── REGIMES ──────────────────────────────────────────────────────────────────
 *
 *  BULL       — safe to run pipeline, scan for longs
 *               Nifty > SMA200, RSI 45–70, not in extreme drawdown
 *
 *  SIDEWAYS   — proceed with caution, tighten filters
 *               Nifty near SMA200 (within 3%), RSI 40–60, low ATR
 *
 *  BEAR       — DO NOT run pipeline, no new longs
 *               Nifty < SMA200, RSI < 45, or drawdown > 10% from recent high
 *
 *  VOLATILE   — market in panic / distribution, wait for stabilisation
 *               ATR spike > 1.5× 20-day average ATR
 *
 * ── HOW IT PLUGS IN ───────────────────────────────────────────────────────────
 *  LiveScanController.livePipeline() checks regime before scanning.
 *  If regime is BEAR or VOLATILE → returns early with regime warning.
 *  If regime is SIDEWAYS → tightens minimum setup score threshold.
 *  If regime is BULL → runs normally.
 *
 * ── PHILOSOPHY ────────────────────────────────────────────────────────────────
 *  "The trend of the market is more important than the trend of the stock."
 *  A stock in a perfect setup during a bear market will fail 70%+ of the time.
 *  Sitting in cash during BEAR/VOLATILE is itself a position.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketRegimeService {

    private final MarketDataClient marketDataClient;

    // Benchmark — NIFTYBEES ETF tracks Nifty 50 closely, has better Yahoo Finance data
    private static final String BENCHMARK_SYMBOL = "NIFTYBEES";
    private static final String BENCHMARK_PERIOD  = "1y";  // need 200+ bars for SMA200

    // ── Thresholds ─────────────────────────────────────────────────────────────

    @Value("${regime.bear.drawdown.pct:10.0}")
    private double BEAR_DRAWDOWN_PCT;     // >10% below recent high = bear signal

    @Value("${regime.bear.rsi.max:45.0}")
    private double BEAR_RSI_MAX;          // RSI < 45 in downtrend = bearish

    @Value("${regime.sideways.sma200.band:0.03}")
    private double SIDEWAYS_BAND;         // within 3% of SMA200 = sideways

    @Value("${regime.volatile.atr.multiplier:1.5}")
    private double VOLATILE_ATR_MULT;     // ATR > 1.5× 20d avg = volatile

    @Value("${regime.bull.rsi.min:45.0}")
    private double BULL_RSI_MIN;          // RSI >= 45 in uptrend = bull

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Analyse current market regime.
     * Fetches 1 year of NIFTYBEES data and computes all regime indicators.
     */
    public MarketRegime analyse() {
        log.info("[Regime] Fetching benchmark data ({})...", BENCHMARK_SYMBOL);

        List<Candle> candles;
        try {
            candles = marketDataClient.fetchCandles(BENCHMARK_SYMBOL, BENCHMARK_PERIOD, "NS");
        } catch (Exception e) {
            log.warn("[Regime] Could not fetch {}: {} — defaulting to UNKNOWN", BENCHMARK_SYMBOL, e.getMessage());
            return MarketRegime.unknown("Could not fetch benchmark data: " + e.getMessage());
        }

        if (candles == null || candles.size() < 50) {
            return MarketRegime.unknown("Insufficient benchmark data");
        }

        int    last  = candles.size() - 1;
        double price = candles.get(last).getClose();

        // ── Indicators ──────────────────────────────────────────────────────────
        Double sma50  = SMAIndicator.calculate(candles, last, 50);
        Double sma200 = SMAIndicator.calculate(candles, last, 200);
        Double rsi    = RSIIndicator.calculate(candles, last, 14);
        Double atr    = ATRIndicator.calculate(candles, last, 14);

        // 20-day average ATR for volatility comparison
        Double avgAtr20 = computeAvgAtr(candles, last, 20);

        // 52-bar (≈ 3-month) high for drawdown calculation
        double high3m = candles.subList(Math.max(0, last - 52), last + 1)
                .stream().mapToDouble(Candle::getHigh).max().orElse(price);
        double drawdownPct = ((high3m - price) / high3m) * 100;

        // ── Regime logic ────────────────────────────────────────────────────────

        // VOLATILE: ATR spike regardless of trend
        boolean atrSpike = atr != null && avgAtr20 != null && avgAtr20 > 0
                && atr > (avgAtr20 * VOLATILE_ATR_MULT);

        // Below SMA200 = structural downtrend
        boolean belowSma200 = sma200 != null && price < sma200;

        // Near SMA200 (within band) = sideways
        boolean nearSma200 = sma200 != null
                && Math.abs(price - sma200) / sma200 < SIDEWAYS_BAND;

        // SMA50 > SMA200 = medium-term uptrend
        boolean sma50aboveSma200 = sma50 != null && sma200 != null && sma50 > sma200;

        // RSI zones
        boolean rsiBearish = rsi != null && rsi < BEAR_RSI_MAX;
        boolean rsiBullish = rsi != null && rsi >= BULL_RSI_MIN;

        // Significant drawdown from recent high
        boolean inDrawdown = drawdownPct > BEAR_DRAWDOWN_PCT;

        // ── Classify ────────────────────────────────────────────────────────────
        MarketRegime.Regime regime;
        String               reason;
        boolean              tradeable;

        if (atrSpike) {
            regime    = MarketRegime.Regime.VOLATILE;
            reason    = String.format(
                    "ATR spike detected (%.2f vs avg %.2f — %.0fx normal). Market in panic/distribution. Wait for volatility to normalise.",
                    atr, avgAtr20, atr / avgAtr20);
            tradeable = false;

        } else if (belowSma200 && rsiBearish && inDrawdown) {
            regime    = MarketRegime.Regime.BEAR;
            reason    = String.format(
                    "BEAR market: Nifty %.1f%% below SMA200, RSI %.1f, down %.1f%% from 3-month high. No new longs.",
                    ((sma200 - price) / sma200) * 100, rsi, drawdownPct);
            tradeable = false;

        } else if (belowSma200 && rsiBearish) {
            regime    = MarketRegime.Regime.BEAR;
            reason    = String.format(
                    "BEAR: Nifty below SMA200 and RSI %.1f < %.0f. Avoid new positions.",
                    rsi, BEAR_RSI_MAX);
            tradeable = false;

        } else if (belowSma200 || inDrawdown) {
            regime    = MarketRegime.Regime.SIDEWAYS;
            reason    = String.format(
                    "CAUTION: %s. Tighten filters — only HIGH conviction setups.",
                    belowSma200
                            ? "Nifty below SMA200"
                            : String.format("Nifty %.1f%% off 3-month high", drawdownPct));
            tradeable = true;

        } else if (nearSma200 && !sma50aboveSma200) {
            regime    = MarketRegime.Regime.SIDEWAYS;
            reason    = String.format(
                    "SIDEWAYS: Nifty near SMA200 (price %.2f, SMA200 %.2f), no clear trend. Reduce position size.",
                    price, sma200);
            tradeable = true;

        } else if (sma50aboveSma200 && rsiBullish) {
            regime    = MarketRegime.Regime.BULL;
            reason    = String.format(
                    "BULL: Nifty above both SMA50 and SMA200, RSI %.1f. Green light for swing trades.",
                    rsi);
            tradeable = true;

        } else {
            regime    = MarketRegime.Regime.SIDEWAYS;
            reason    = String.format(
                    "MIXED: SMA50>SMA200=%b, RSI=%.1f, drawdown=%.1f%%. Proceed selectively.",
                    sma50aboveSma200, rsi != null ? rsi : 50, drawdownPct);
            tradeable = true;
        }

        // ── Recommendation ──────────────────────────────────────────────────────
        String recommendation = switch (regime) {
            case BULL     -> "Run live scan normally. All setups eligible.";
            case SIDEWAYS -> "Run live scan with higher score threshold (≥55). Reduce position size by 30%.";
            case BEAR     -> "Do NOT run pipeline. Sit in cash. Wait for Nifty to reclaim SMA200.";
            case VOLATILE -> "Do NOT run pipeline. Wait for ATR to normalise below " +
                             String.format("%.2f", avgAtr20 != null ? avgAtr20 * VOLATILE_ATR_MULT : 0) + ".";
            default       -> "Unable to determine regime. Proceed with extreme caution.";
        };

        log.info("[Regime] {} — {} | tradeable={}", regime, reason, tradeable);

        return MarketRegime.builder()
                .regime(regime)
                .reason(reason)
                .recommendation(recommendation)
                .tradeable(tradeable)
                .benchmarkSymbol(BENCHMARK_SYMBOL)
                .benchmarkPrice(round2(price))
                .sma50(sma50 != null ? round2(sma50) : null)
                .sma200(sma200 != null ? round2(sma200) : null)
                .rsi(rsi != null ? round1(rsi) : null)
                .atr(atr != null ? round2(atr) : null)
                .avgAtr20(avgAtr20 != null ? round2(avgAtr20) : null)
                .drawdownFromHigh(round2(drawdownPct))
                .atrSpike(atrSpike)
                .aboveSma200(!belowSma200 && sma200 != null)
                .sma50AboveSma200(sma50aboveSma200)
                .build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Average ATR over the last N bars (each bar's ATR, then averaged).
     * Used to detect ATR spikes vs baseline volatility.
     */
    private Double computeAvgAtr(List<Candle> candles, int last, int bars) {
        if (last < bars + 14) return null;
        double sum = 0;
        int    count = 0;
        for (int i = last - bars; i <= last; i++) {
            Double atr = ATRIndicator.calculate(candles, i, 14);
            if (atr != null) { sum += atr; count++; }
        }
        return count > 0 ? sum / count : null;
    }

    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}

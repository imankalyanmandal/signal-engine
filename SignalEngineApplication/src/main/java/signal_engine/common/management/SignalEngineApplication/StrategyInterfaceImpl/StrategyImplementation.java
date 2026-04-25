package signal_engine.common.management.SignalEngineApplication.StrategyInterfaceImpl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import signal_engine.common.management.SignalEngineApplication.Indicators.ATRIndicator;
import signal_engine.common.management.SignalEngineApplication.Indicators.BollingerBandsIndicator;
import signal_engine.common.management.SignalEngineApplication.Indicators.RSIIndicator;
import signal_engine.common.management.SignalEngineApplication.Indicators.SMAIndicator;
import signal_engine.common.management.SignalEngineApplication.StrategyInterface.Strategy;
import signal_engine.common.management.SignalEngineApplication.model.Candle;

import java.util.List;

/**
 * Upgraded swing trading strategy — multi-signal confirmation.
 *
 * ── BUY CONDITIONS (any one pathway triggers BUY) ───────────────────────────
 *
 *  Pathway 1 — RSI Oversold + SMA200 trend filter
 *    RSI < 30  AND  price > SMA200
 *    → Oversold but in a long-term uptrend (buy the dip, not a falling knife)
 *    → Volume must be ≥ 1.5× 20-day average (institutional confirmation)
 *
 *  Pathway 2 — Golden Cross + Volume Spike + ATR Rising
 *    SMA20 crosses above SMA50 (this bar) AND volume > 1.5× avg AND ATR rising
 *    → A fresh golden cross with volume and expanding volatility = high momentum entry
 *    → ATR rising means the breakout has energy behind it
 *
 *  Pathway 3 — Bollinger Band Squeeze + Breakout
 *    Band squeeze detected (bandwidth < 5% of price for last 5 bars) AND
 *    price closes above upper band (breakout from squeeze)
 *    → Contraction followed by expansion = reliable breakout signal
 *    → Volume must confirm: > 1.5× average
 *
 * ── SELL CONDITIONS (any one pathway triggers SELL) ─────────────────────────
 *
 *  Pathway A — RSI Overbought + Below SMA200
 *    RSI > 70  AND  price < SMA200
 *    → Technically extended and losing long-term support
 *
 *  Pathway B — Death Cross
 *    SMA20 crosses below SMA50 (this bar)
 *    → Trend reversal confirmed
 *
 *  Pathway C — Price breaks below SMA20 with weak momentum
 *    price < SMA20 AND RSI < 50 AND volume > 1.0× avg
 *    → Lost short-term support with momentum fading
 *
 * ── VOLUME FILTER ────────────────────────────────────────────────────────────
 *  All BUY signals require volume > 1.5× 20-day average.
 *  SELL signals require volume > 1.0× average (lower threshold — exits are easier).
 *
 * ── WARMUP REQUIREMENTS ──────────────────────────────────────────────────────
 *  SMA200 needs 200 bars → need at least 200 candles in backtest data.
 *  Use period=2y or period=5y for backtest. 3mo live scan uses pathway 2+3 only.
 */
@Component
public class StrategyImplementation implements Strategy {

    // ── Thresholds (configurable via application.properties) ──────────────────

    @Value("${strategy.rsi.oversold:30}")
    private double RSI_OVERSOLD;

    @Value("${strategy.rsi.overbought:70}")
    private double RSI_OVERBOUGHT;

    @Value("${strategy.volume.buy.multiplier:1.5}")
    private double VOLUME_BUY_MULT;        // BUY needs volume > 1.5× avg

    @Value("${strategy.volume.sell.multiplier:1.0}")
    private double VOLUME_SELL_MULT;       // SELL needs volume > 1.0× avg

    @Value("${strategy.bb.squeeze.threshold:0.05}")
    private double BB_SQUEEZE_THRESHOLD;   // bandwidth < 5% of price = squeeze

    @Value("${strategy.bb.squeeze.bars:5}")
    private int BB_SQUEEZE_BARS;           // how many bars to check for squeeze

    @Value("${strategy.atr.rising.bars:3}")
    private int ATR_RISING_BARS;           // ATR must be higher than N bars ago

    // Expose current ATR for BacktestService to use in stop calculation
    public static final ThreadLocal<Double> currentATR = new ThreadLocal<>();

    // ── Main signal generation ─────────────────────────────────────────────────

    @Override
    public String generateSignal(List<Candle> candles, int index) {

        // ── 1. Compute all indicators ──────────────────────────────────────────
        Double rsi    = RSIIndicator.calculate(candles, index, 14);
        Double sma20  = SMAIndicator.calculate(candles, index, 20);
        Double sma50  = SMAIndicator.calculate(candles, index, 50);
        Double sma200 = SMAIndicator.calculate(candles, index, 200);
        Double atr    = ATRIndicator.calculate(candles, index, 14);
        Double[] bb   = BollingerBandsIndicator.calculate(candles, index, 20, 2.0);

        // Store ATR for BacktestService stop-loss calculation
        currentATR.set(atr);

        double price = candles.get(index).getClose();

        // Minimum warmup: RSI(14) + SMA(50) must be ready
        if (rsi == null || sma20 == null || sma50 == null) return "HOLD";

        // ── 2. Volume vs 20-day average ────────────────────────────────────────
        double volumeRatio = computeVolumeRatio(candles, index, 20);

        // ── 3. Previous bar indicators (for crossover detection) ───────────────
        Double prevSma20 = index > 0 ? SMAIndicator.calculate(candles, index - 1, 20) : null;
        Double prevSma50 = index > 0 ? SMAIndicator.calculate(candles, index - 1, 50) : null;

        // ── 4. ATR trend ────────────────────────────────────────────────────────
        boolean atrRising = isAtrRising(candles, index);

        // ── 5. Bollinger Band states ───────────────────────────────────────────
        boolean bbSqueeze   = isBollingerSqueeze(candles, index);
        boolean bbBreakout  = (bb != null && price > bb[2]); // close above upper band
        boolean bbLowerTouch = (bb != null && price < bb[0]); // at or below lower band

        // ═══════════════════════════════════════════════════════════════════════
        // BUY PATHWAYS
        // ═══════════════════════════════════════════════════════════════════════

        // Pathway 1: RSI oversold + SMA200 trend + volume confirmation
        if (rsi < RSI_OVERSOLD && sma200 != null && price > sma200
                && volumeRatio >= VOLUME_BUY_MULT) {
            return "BUY";
        }

        // Pathway 1b: RSI oversold + at Bollinger lower band (no SMA200 needed — works with 3mo data)
        if (rsi < RSI_OVERSOLD && bbLowerTouch && volumeRatio >= VOLUME_BUY_MULT) {
            return "BUY";
        }

        // Pathway 2: Fresh Golden Cross + volume spike + ATR rising
        if (prevSma20 != null && prevSma50 != null
                && prevSma20 <= prevSma50   // previous: SMA20 was below SMA50
                && sma20 > sma50            // current:  SMA20 crossed above SMA50
                && volumeRatio >= VOLUME_BUY_MULT
                && atrRising) {
            return "BUY";
        }

        // Pathway 3: Bollinger Band squeeze breakout
        if (bbSqueeze && bbBreakout && volumeRatio >= VOLUME_BUY_MULT) {
            return "BUY";
        }

        // ═══════════════════════════════════════════════════════════════════════
        // SELL PATHWAYS
        // ═══════════════════════════════════════════════════════════════════════

        // Pathway A: RSI overbought + below SMA200 (extended AND losing LT support)
        if (rsi > RSI_OVERBOUGHT && sma200 != null && price < sma200
                && volumeRatio >= VOLUME_SELL_MULT) {
            return "SELL";
        }

        // Pathway A2: RSI overbought alone (SMA200 not required — works with 3mo data)
        if (rsi > RSI_OVERBOUGHT && volumeRatio >= VOLUME_SELL_MULT) {
            return "SELL";
        }

        // Pathway B: Fresh Death Cross
        if (prevSma20 != null && prevSma50 != null
                && prevSma20 >= prevSma50   // previous: SMA20 was above SMA50
                && sma20 < sma50) {         // current:  SMA20 crossed below SMA50
            return "SELL";
        }

        // Pathway C: Below SMA20 + weak momentum + volume confirms selling
        if (price < sma20 && rsi < 50 && volumeRatio >= VOLUME_SELL_MULT) {
            return "SELL";
        }

        return "HOLD";
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Volume ratio = today's volume / 20-day average volume.
     * Returns 0 if insufficient data.
     */
    private double computeVolumeRatio(List<Candle> candles, int index, int period) {
        if (index < period) return 0;
        double avgVolume = 0;
        for (int i = index - period; i < index; i++) {    // average excludes today
            avgVolume += candles.get(i).getVolume();
        }
        avgVolume /= period;
        if (avgVolume == 0) return 0;
        return candles.get(index).getVolume() / avgVolume;
    }

    /**
     * ATR rising = current ATR > ATR N bars ago.
     * Expanding ATR means the move has momentum/energy behind it.
     */
    private boolean isAtrRising(List<Candle> candles, int index) {
        if (index < ATR_RISING_BARS + 14) return false;
        Double atrNow  = ATRIndicator.calculate(candles, index, 14);
        Double atrPrev = ATRIndicator.calculate(candles, index - ATR_RISING_BARS, 14);
        return atrNow != null && atrPrev != null && atrNow > atrPrev;
    }

    /**
     * Bollinger Band squeeze = bandwidth has been narrow for the last N bars.
     * Bandwidth = (upperBand - lowerBand) / middleBand.
     * Squeeze = bandwidth < threshold for all N bars.
     */
    private boolean isBollingerSqueeze(List<Candle> candles, int index) {
        if (index < BB_SQUEEZE_BARS + 20) return false;
        for (int i = index - BB_SQUEEZE_BARS; i <= index; i++) {
            Double[] bands = BollingerBandsIndicator.calculate(candles, i, 20, 2.0);
            if (bands == null || bands[1] == 0) return false;
            double bandwidth = (bands[2] - bands[0]) / bands[1];
            if (bandwidth >= BB_SQUEEZE_THRESHOLD) return false; // too wide — not a squeeze
        }
        return true; // all N bars had narrow bandwidth
    }
}
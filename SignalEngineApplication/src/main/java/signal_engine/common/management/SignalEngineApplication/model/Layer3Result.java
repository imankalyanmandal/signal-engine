package signal_engine.common.management.SignalEngineApplication.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Layer 3 result — precise entry/stop/target refinement for a swing trade.
 *
 * Computed from actual price structure (support/resistance levels,
 * candlestick patterns, ATR-based stop placement).
 *
 * Replaces the rough approximations from LiveScanResult:
 *   entryLow  = price × 0.99    →  nearest support or pullback level
 *   stopLoss  = entry − 2×ATR   →  below nearest swing low + buffer
 *   target    = entry + 4×ATR   →  nearest resistance level
 */
@Data
@Builder
public class Layer3Result {

    // ── Identity ──────────────────────────────────────────────────────────────
    private String symbol;
    private String sector;
    private double currentPrice;
    private double atr;

    // ── Support / Resistance levels ───────────────────────────────────────────
    private List<SRLevel> supportLevels;    // top 3 below current price
    private List<SRLevel> resistanceLevels; // top 3 above current price
    private Double        nearest52wHigh;
    private Double        pctTo52wHigh;

    // ── Precise trade parameters ──────────────────────────────────────────────
    private double entryLow;         // ideal entry (pullback to support/SMA)
    private double entryHigh;        // breakout entry (above recent high)
    private double stopLoss;         // below nearest swing low + 0.5×ATR buffer
    private String stopMethod;       // SUPPORT_BASED | ATR_BASED
    private Double stopLevel;        // the support level the stop is based on (if SUPPORT_BASED)

    private double target1;          // first target: nearest resistance
    private double target2;          // stretch target: next resistance / 52w high
    private String target1Method;    // RESISTANCE_BASED | ATR_PROJECTION
    private String target2Method;

    // ── Risk metrics ──────────────────────────────────────────────────────────
    private double riskPct;          // (entryLow - stopLoss) / entryLow × 100
    private double rewardPct1;       // (target1 - entryLow) / entryLow × 100
    private double rewardPct2;       // (target2 - entryLow) / entryLow × 100
    private double rrRatio1;         // reward1 / risk
    private double rrRatio2;         // reward2 / risk
    private int    suggestedHoldDays;// estimated bars to target1 based on ATR velocity

    // ── Candlestick pattern at current bar ────────────────────────────────────
    private String  entrySignal;     // ENTER_NOW | WAIT_FOR_CONFIRMATION | AVOID
    private String  candlePattern;   // HAMMER | BULLISH_ENGULFING | MORNING_STAR | INSIDE_BAR | PIN_BAR | NONE
    private boolean atSupport;       // current price within 2% of a support level
    private String  entryTiming;     // NOW | ON_PULLBACK | ON_BREAKOUT

    // ── Summary ───────────────────────────────────────────────────────────────
    private String tradeNote;        // one-line summary for the trader

    // ── Error state ───────────────────────────────────────────────────────────
    private boolean error;
    private String  errorMessage;

    // ── Nested S/R level ──────────────────────────────────────────────────────
    @Data
    @Builder
    public static class SRLevel {
        private double price;
        private int    strength;     // how many times this level was touched
        private int    barsAgo;      // most recent touch
        private String type;         // SUPPORT | RESISTANCE
    }
}

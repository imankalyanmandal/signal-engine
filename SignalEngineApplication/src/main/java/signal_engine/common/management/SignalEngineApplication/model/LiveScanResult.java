package signal_engine.common.management.SignalEngineApplication.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Result of a live indicator scan for a single stock.
 * Answers: is this stock in a high-conviction swing trade setup RIGHT NOW?
 */
@Data
@Builder
public class LiveScanResult {

    // ── Identity ──────────────────────────────────────────────────────────────
    private String symbol;
    private String sector;       // from NiftySectorMap — used for deduplication
    private double price;

    // ── Core indicators ───────────────────────────────────────────────────────
    private double rsi;
    private double sma20;
    private double sma50;
    private double atr;
    private double volumeRatio;  // today / 20d average

    // ── Relative strength ─────────────────────────────────────────────────────
    private double stockReturn20d;    // stock's 20d return %
    private double niftyReturn20d;    // Nifty benchmark 20d return %
    private double relativeStrength;  // stock - nifty (positive = outperforming)

    // ── Boolean conditions (for quick UI display) ─────────────────────────────
    private boolean uptrend;           // SMA20 > SMA50
    private boolean aboveSma50;        // price > SMA50
    private boolean aboveSma20;        // price > SMA20
    private boolean pullbackToSma20;   // price within 3% of SMA20
    private boolean sma50SlopePositive; // SMA50 trending up over last 10 bars
    private boolean higherHigh;        // price structure: new high vs prior period
    private boolean higherLow;         // price structure: higher low vs prior period
    private boolean rsiOversold;       // RSI in sweet spot (30-45)
    private boolean volumeConfirmed;   // volume >= 1.2x average
    private boolean nearResistance;    // within 8% of 52-week high (warning)

    // ── Momentum ──────────────────────────────────────────────────────────────
    private int    greenCandles;       // green candles in last 10 bars
    private double pctTo52wHigh;       // % distance below 52-week high

    // ── Trade parameters ──────────────────────────────────────────────────────
    private double entryLow;
    private double entryHigh;
    private double stopLoss;    // entry - (2 × ATR)
    private double target;      // entry + (2 × ATR × RR ratio)
    private double riskPct;     // stop distance as % of price
    private double rewardPct;   // target distance as % of price
    private double rrRatio;     // reward / risk

    // ── Assessment ────────────────────────────────────────────────────────────
    private int     setupScore;   // 0-100
    private boolean isSetup;      // true if score >= 40 and all gates passed
    private String  verdict;      // STRONG SETUP | SETUP | WEAK SETUP | NO SETUP | BLOCKED
    private String  conviction;   // HIGH | MEDIUM | LOW

    // ── Explanation ───────────────────────────────────────────────────────────
    private List<String> conditions;  // what's working for this setup
    private List<String> warnings;    // gate failures or caution flags

    // ── Error state ───────────────────────────────────────────────────────────
    private boolean error;
    private String  errorMessage;

    // ── Static factories ──────────────────────────────────────────────────────
    public static LiveScanResult error(String symbol, String message) {
        return LiveScanResult.builder()
                .symbol(symbol)
                .sector("UNKNOWN")
                .error(true)
                .errorMessage(message)
                .isSetup(false)
                .verdict("ERROR")
                .conviction("LOW")
                .conditions(List.of())
                .warnings(List.of(message))
                .build();
    }
}
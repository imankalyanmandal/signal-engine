package signal_engine.common.management.SignalEngineApplication.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Result of a live scan analysis for a single stock.
 *
 * Contains all raw indicators, gate outcomes, setup type (PULLBACK/BREAKOUT),
 * score, verdict, and ATR-based entry/stop/target.
 */
@Data
@Builder
public class LiveScanResult {

    // ── Identity ──────────────────────────────────────────────────────────────
    private String symbol;
    private String sector;
    private double price;

    // ── Core indicators ───────────────────────────────────────────────────────
    private double  rsi;
    private double  sma20;
    private double  sma50;
    private Double  sma200;         // null if data period < 200 bars
    private double  atr;
    private double  atrPct;         // ATR as % of price
    private double  volumeRatio;    // today / 20d avg

    // ── Relative strength ─────────────────────────────────────────────────────
    private double  stockReturn20d;
    private double  niftyReturn20d;
    private double  relativeStrength; // stock - nifty (positive = outperforming)

    // ── Gate outcomes (booleans) ──────────────────────────────────────────────
    private boolean sma50SlopePositive;
    private boolean aboveSma50;
    private boolean aboveSma20;
    private boolean aboveSma200;
    private boolean higherHigh;
    private boolean higherLow;
    private boolean nearResistance;   // within 5% of 52-week high
    private boolean rsiOversold;      // RSI in pullback zone (35-50)
    private boolean volumeConfirmed;  // volume above required threshold

    // ── Distance metrics ──────────────────────────────────────────────────────
    private double  pctTo52wHigh;    // % below 52-week high (higher = more room)

    // ── Setup classification ──────────────────────────────────────────────────
    private String  setupType;       // PULLBACK | BREAKOUT | BLOCKED
    private int     setupScore;      // 0-100
    private boolean isSetup;         // true if score >= threshold and gates passed
    private String  verdict;         // STRONG PULLBACK | PULLBACK | BREAKOUT | WEAK PULLBACK | BLOCKED
    private String  conviction;      // HIGH | MEDIUM | LOW

    // ── Trade parameters (ATR-based) ──────────────────────────────────────────
    private double  entryLow;
    private double  entryHigh;
    private double  stopLoss;
    private double  target;
    private double  riskPct;
    private double  rewardPct;
    private double  rrRatio;

    // ── Explanation ───────────────────────────────────────────────────────────
    private List<String> conditions;  // what's confirming this setup
    private List<String> warnings;    // gate failures or caution flags

    // ── Error ─────────────────────────────────────────────────────────────────
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
                .setupType("ERROR")
                .verdict("ERROR")
                .conviction("LOW")
                .conditions(List.of())
                .warnings(List.of(message))
                .build();
    }
}
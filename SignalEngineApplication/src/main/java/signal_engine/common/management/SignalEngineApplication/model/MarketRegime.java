package signal_engine.common.management.SignalEngineApplication.model;

import lombok.Builder;
import lombok.Data;

/**
 * Result of a market regime analysis.
 * Used to gate the live scan pipeline — BEAR/VOLATILE = no new trades.
 */
@Data
@Builder
public class MarketRegime {

    public enum Regime {
        BULL,       // uptrend, safe to trade long
        SIDEWAYS,   // no clear trend, trade selectively with higher bar
        BEAR,       // downtrend, do NOT open new long positions
        VOLATILE,   // panic/spike, wait for normalisation
        UNKNOWN     // could not determine (data unavailable)
    }

    // ── Core verdict ──────────────────────────────────────────────────────────
    private Regime  regime;
    private String  reason;          // plain English explanation
    private String  recommendation;  // actionable instruction
    private boolean tradeable;       // false for BEAR and VOLATILE

    // ── Benchmark data ────────────────────────────────────────────────────────
    private String  benchmarkSymbol;
    private double  benchmarkPrice;
    private Double  sma50;
    private Double  sma200;
    private Double  rsi;
    private Double  atr;
    private Double  avgAtr20;
    private double  drawdownFromHigh;  // % below 3-month high

    // ── Boolean flags ──────────────────────────────────────────────────────────
    private boolean aboveSma200;
    private boolean sma50AboveSma200;
    private boolean atrSpike;

    // ── Adjusted scan threshold based on regime ───────────────────────────────
    public int getMinSetupScore() {
        return switch (regime) {
            case BULL     -> 40;   // normal threshold
            case SIDEWAYS -> 55;   // tighter — only good setups
            case BEAR, VOLATILE, UNKNOWN -> 100; // effectively blocks all setups
        };
    }

    // ── Static factories ──────────────────────────────────────────────────────

    public static MarketRegime unknown(String reason) {
        return MarketRegime.builder()
                .regime(Regime.UNKNOWN)
                .reason(reason)
                .recommendation("Proceed with extreme caution — regime unknown.")
                .tradeable(false)
                .drawdownFromHigh(0)
                .aboveSma200(false)
                .sma50AboveSma200(false)
                .atrSpike(false)
                .build();
    }
}

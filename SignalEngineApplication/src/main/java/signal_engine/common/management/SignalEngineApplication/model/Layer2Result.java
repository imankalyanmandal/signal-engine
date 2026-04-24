package signal_engine.common.management.SignalEngineApplication.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Layer 2 composite result from the LLM-powered scorer.
 * Deserialised from the Python microservice /layer2/analyse response.
 *
 * Fields used by TradeService.createSignalFromLayer2():
 *   getSymbol(), getCompanyName(), getCompositeScore(),
 *   getSwingVerdict(), getConviction(), getRationale(), getEntryNote()
 */
@Data
public class Layer2Result {

    // ── Identity ──────────────────────────────────────────────────────────────
    private String  symbol;
    private String  companyName;

    // ── Gate result ───────────────────────────────────────────────────────────
    private boolean layer2Pass;
    private int     compositeScore;
    private int     passThreshold;

    // ── LLM verdict ───────────────────────────────────────────────────────────
    private String swingVerdict;        // STRONG_BUY | BUY | HOLD | AVOID | STRONG_AVOID
    private String conviction;          // HIGH | MEDIUM | LOW
    private String signalAlignment;     // ALIGNED | MIXED | CONFLICTED
    private String rationale;
    private String optimalEntryTiming;  // NOW | WAIT_FOR_PULLBACK | WAIT_FOR_CATALYST | NOT_NOW
    private String entryNote;

    private List<String> keyPositives;
    private List<String> keyRisks;
    private List<String> redFlags;

    // ── Context-adaptive weights ──────────────────────────────────────────────
    private Map<String, Integer> weightsUsed;

    // ── Raw sub-results (for transparency / UI display) ───────────────────────
    private FundamentalDetail fundamentals;
    private SentimentDetail   sentiment;
    private ConcallDetail     concall;

    // ── Meta ──────────────────────────────────────────────────────────────────
    private boolean claudeFallback;
    private boolean mock;

    // ── Nested detail classes ─────────────────────────────────────────────────

    @Data
    public static class FundamentalDetail {
        private Double       roe;
        private Double       debtEquity;
        private Double       promoterHolding;
        private Double       revenueGrowth3y;
        private Double       profitGrowth3y;
        private Double       fcfLatestCr;
        private List<Double> revenueSeries;
        private List<Double> debtSeries;
        private List<Double> ocfSeries;
        private List<Double> promoterSeries;
        private List<String> trendNotes;
        private List<String> flags;
    }

    @Data
    public static class SentimentDetail {
        private String       direction;      // BULLISH | NEUTRAL | BEARISH
        private String       confidence;     // HIGH | MEDIUM | LOW
        private String       narrative;
        private List<String> topHeadlines;
    }

    @Data
    public static class ConcallDetail {
        private String tone;            // CONFIDENT | CAUTIOUS | CONCERNED
        private String guidanceChange;  // RAISED | MAINTAINED | LOWERED | NOT_GIVEN
        private String swingSignal;     // BUY_BIAS | HOLD_BIAS | SELL_BIAS | NEUTRAL
        private String summary;
        private String filingDate;
    }
}
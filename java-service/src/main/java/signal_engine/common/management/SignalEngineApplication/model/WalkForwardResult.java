package signal_engine.common.management.SignalEngineApplication.model;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * Result of a walk-forward validation run.
 *
 * Aggregate metrics summarise the AVERAGE behaviour across all test windows —
 * that average is the realistic estimate of how the strategy would have
 * performed if traded live.
 *
 * The KEY metric is `degradation`:
 *   train avg return - test avg return
 * Small (< 25% of train) = robust. Large (> 50%) = overfitting.
 */
@Data
@Builder
public class WalkForwardResult {

    private String stockSymbol;
    private List<WalkForwardWindow> windows;

    private double avgTrainReturn;
    private double avgTestReturn;
    private double degradation;
    private double avgTestSharpe;
    private double avgTestWinRate;
    private int    totalTestTrades;
    private double worstTestReturn;
    private double bestTestReturn;
    private double buyAndHoldReturn;

    public String getRobustnessAssessment() {
        if (windows == null || windows.isEmpty())     return "INSUFFICIENT DATA";

        // Need at least ~2 trades per window on average, minimum 5 total.
        // Strategies that fire rarely (e.g. mean-reversion) can be valid with
        // few trades — but below this threshold there's just not enough sample.
        int minTrades = Math.max(5, windows.size() * 2);
        if (totalTestTrades < minTrades) {
            return "LOW TRADE COUNT — only " + totalTestTrades + " trades across "
                 + windows.size() + " windows. Strategy may not fire often enough on this stock.";
        }

        if (avgTestReturn < 0)                        return "FAIL — strategy loses money out-of-sample";
        if (avgTrainReturn <= 0)                      return "FAIR — train return non-positive, hard to assess";
        if (degradation > avgTrainReturn * 0.75)      return "POOR — heavy degradation suggests overfitting";
        if (degradation > avgTrainReturn * 0.50)      return "FAIR — moderate degradation, trade with caution";
        if (degradation > avgTrainReturn * 0.25)      return "GOOD — modest degradation, strategy looks robust";
        return                                               "EXCELLENT — minimal degradation, strong out-of-sample edge";
    }
}

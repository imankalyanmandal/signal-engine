package signal_engine.common.management.SignalEngineApplication.model;

import lombok.Builder;
import lombok.Data;

/**
 * A single (train, test) window from a walk-forward validation run.
 *
 * Each window has its own backtest result for the train period AND for the
 * test period. The train result is informational. The TEST result is the
 * realistic one — it shows how the strategy performed on data it had never
 * seen.
 */
@Data
@Builder
public class WalkForwardWindow {

    private int    windowNumber;
    private String trainStart;
    private String trainEnd;
    private String testStart;
    private String testEnd;

    private double trainReturn;
    private double trainSharpe;
    private double trainWinRate;
    private int    trainTrades;

    private double testReturn;
    private double testSharpe;
    private double testWinRate;
    private int    testTrades;
    private double testMaxDrawdown;

    public double getWindowDegradation() {
        return trainReturn - testReturn;
    }
}

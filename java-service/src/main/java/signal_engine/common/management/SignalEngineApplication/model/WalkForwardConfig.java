package signal_engine.common.management.SignalEngineApplication.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Request body for the walk-forward backtest endpoint.
 *
 * Walk-forward validation rolls a fixed-size training window forward through
 * the data. Each test window is genuinely out-of-sample relative to its
 * preceding train window — the point is to detect overfitting: if the
 * strategy works on 2019 train data but loses on 2020 test data, that's
 * a red flag.
 *
 * Defaults: 24-month train, 6-month test, 6-month roll forward.
 *   With 5y of data this produces ~7 walk windows.
 */
@Getter
@Setter
public class WalkForwardConfig {

    // ── Window sizing (in trading days) ──────────────────────────────────────
    private int trainDays = 504;     // ~24 months
    private int testDays  = 126;     // ~6 months
    private int rollDays  = 126;     // ~6 months — non-overlapping test windows

    // ── Risk parameters (override BacktestService defaults per run) ───────────
    private double riskPerTrade  = 0.01;   // 1% per trade
    private double atrMultiplier = 2.0;
    private double takeProfitRR  = 2.0;

    // ── Cost overrides ────────────────────────────────────────────────────────
    private double entrySlippage  = 0.0025;  // 0.25% slippage
    private double exitSlippage   = 0.0025;
    private double initialCapital = 100000;

    public void clampToReasonableBounds() {
        if (trainDays      < 60   || trainDays      > 2000)  trainDays      = 504;
        if (testDays       < 30   || testDays       > 1000)  testDays       = 126;
        if (rollDays       < 10   || rollDays       > 1000)  rollDays       = testDays;
        if (riskPerTrade   < 0.001 || riskPerTrade   > 0.05) riskPerTrade   = 0.01;
        if (atrMultiplier  < 0.5  || atrMultiplier  > 10)    atrMultiplier  = 2.0;
        if (takeProfitRR   < 0.5  || takeProfitRR   > 10)    takeProfitRR   = 2.0;
        if (entrySlippage  < 0    || entrySlippage  > 0.05)  entrySlippage  = 0.0025;
        if (exitSlippage   < 0    || exitSlippage   > 0.05)  exitSlippage   = 0.0025;
        if (initialCapital < 1000 || initialCapital > 1e9)   initialCapital = 100000;
    }
}

package signal_engine.common.management.SignalEngineApplication.Service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import signal_engine.common.management.SignalEngineApplication.model.BacktestResult;
import signal_engine.common.management.SignalEngineApplication.model.Candle;
import signal_engine.common.management.SignalEngineApplication.model.WalkForwardConfig;
import signal_engine.common.management.SignalEngineApplication.model.WalkForwardResult;
import signal_engine.common.management.SignalEngineApplication.model.WalkForwardWindow;

/**
 * Walk-Forward Validation Service.
 *
 * A standard backtest fits AND evaluates on the same data, which inflates
 * the apparent edge. Walk-forward splits the data into chronological
 * (train, test) pairs — even though we don't optimise parameters here, the
 * test windows are still genuinely out-of-sample data, so test results
 * approximate live performance better than a single-pass backtest.
 *
 * Algorithm:
 *   1. Slice candles into rolling [train | test] windows of fixed size.
 *   2. Run the backtest separately on each train and test slice.
 *   3. Aggregate test results — that average is the realistic edge estimate.
 *   4. degradation = trainAvg - testAvg. Big degradation = overfitting.
 */
@Service
@RequiredArgsConstructor
public class WalkForwardService {

    private static final Logger log = LoggerFactory.getLogger(WalkForwardService.class);

    private final BacktestService backtestService;

    /**
     * Bars to prepend to each train/test slice so indicators (SMA200, etc.)
     * are warm from the very first bar of the window. Without this, a 126-day
     * test slice produces zero trades because SMA200 is always null.
     *
     * The strategy itself ignores these warmup bars for entry decisions —
     * they only serve to populate the indicator history.
     */
    private static final int WARMUP_BARS = 200;

    public WalkForwardResult run(String stockSymbol, List<Candle> candles, WalkForwardConfig cfg) {
        cfg.clampToReasonableBounds();

        int minRequired = cfg.getTrainDays() + cfg.getTestDays() + WARMUP_BARS;
        if (candles == null || candles.size() < minRequired) {
            throw new IllegalArgumentException(
                "Not enough candles for walk-forward. Need at least "
                + minRequired + " bars (" + cfg.getTrainDays() + " train + "
                + cfg.getTestDays() + " test + " + WARMUP_BARS + " warmup), "
                + "got " + (candles == null ? 0 : candles.size())
                + ". Try a longer period (e.g. 5y) or smaller train/test windows.");
        }

        log.info("Walk-forward {}: {} candles, train={} test={} roll={}",
                 stockSymbol, candles.size(), cfg.getTrainDays(),
                 cfg.getTestDays(), cfg.getRollDays());

        List<WalkForwardWindow> windows = new ArrayList<>();
        int trainDays = cfg.getTrainDays();
        int testDays  = cfg.getTestDays();
        int rollDays  = cfg.getRollDays();

        int windowNumber = 1;
        int trainStart   = WARMUP_BARS;  // first window starts after warmup

        while (trainStart + trainDays + testDays <= candles.size()) {
            int trainEnd = trainStart + trainDays;
            int testEnd  = trainEnd + testDays;

            // Each slice is prepended with WARMUP_BARS of preceding candles so
            // SMA200 and other slow indicators are ready on the first signal bar.
            // BacktestService treats them as normal candles — they pad indicator
            // history but produce few/no trades since most strategies need
            // momentum confirmation that only appears after warmup.
            List<Candle> trainSlice = new ArrayList<>(
                candles.subList(trainStart - WARMUP_BARS, trainEnd));
            List<Candle> testSlice  = new ArrayList<>(
                candles.subList(trainEnd   - WARMUP_BARS, testEnd));

            BacktestResult trainResult;
            BacktestResult testResult;
            try {
                trainResult = backtestService.run(stockSymbol, trainSlice, cfg);
                testResult  = backtestService.run(stockSymbol, testSlice,  cfg);
            } catch (RuntimeException e) {
                log.warn("Window {} failed: {}", windowNumber, e.getMessage());
                trainStart += rollDays;
                windowNumber++;
                continue;
            }

            windows.add(WalkForwardWindow.builder()
                    .windowNumber(windowNumber)
                    // Display dates reflect the tradeable window, excluding warmup bars
                    .trainStart(candles.get(trainStart).getDate())
                    .trainEnd  (candles.get(trainEnd - 1).getDate())
                    .testStart (candles.get(trainEnd).getDate())
                    .testEnd   (candles.get(testEnd - 1).getDate())
                    .trainReturn (trainResult.getReturnPercent())
                    .trainSharpe (trainResult.getSharpeRatio())
                    .trainWinRate(trainResult.getWinRate())
                    .trainTrades (trainResult.getTotalTrades())
                    .testReturn     (testResult.getReturnPercent())
                    .testSharpe     (testResult.getSharpeRatio())
                    .testWinRate    (testResult.getWinRate())
                    .testTrades     (testResult.getTotalTrades())
                    .testMaxDrawdown(testResult.getMaxDrawdown())
                    .build());

            log.debug("Window {}: train={}% test={}%",
                     windowNumber,
                     String.format("%.2f", trainResult.getReturnPercent()),
                     String.format("%.2f", testResult.getReturnPercent()));

            trainStart += rollDays;
            windowNumber++;
        }

        if (windows.isEmpty()) {
            throw new IllegalStateException("No valid walk-forward windows produced");
        }

        // ── Aggregate ─────────────────────────────────────────────────────────
        double avgTrain   = windows.stream().mapToDouble(WalkForwardWindow::getTrainReturn ).average().orElse(0);
        double avgTest    = windows.stream().mapToDouble(WalkForwardWindow::getTestReturn  ).average().orElse(0);
        double avgSharpe  = windows.stream().mapToDouble(WalkForwardWindow::getTestSharpe  ).average().orElse(0);
        double avgWinRate = windows.stream().mapToDouble(WalkForwardWindow::getTestWinRate ).average().orElse(0);
        int    totalTrades= windows.stream().mapToInt   (WalkForwardWindow::getTestTrades  ).sum();
        double worstTest  = windows.stream().mapToDouble(WalkForwardWindow::getTestReturn  ).min().orElse(0);
        double bestTest   = windows.stream().mapToDouble(WalkForwardWindow::getTestReturn  ).max().orElse(0);

        // Buy-and-hold over the full TRADEABLE period (excluding warmup)
        int firstIdx = WARMUP_BARS;
        int lastIdx  = Math.min(candles.size() - 1,
                                WARMUP_BARS + trainDays + testDays + (windows.size() - 1) * rollDays - 1);
        double bhFirst = candles.get(firstIdx).getClose();
        double bhLast  = candles.get(lastIdx).getClose();
        double buyAndHold = bhFirst > 0 ? ((bhLast - bhFirst) / bhFirst) * 100 : 0;

        WalkForwardResult result = WalkForwardResult.builder()
                .stockSymbol(stockSymbol)
                .windows(windows)
                .avgTrainReturn(avgTrain)
                .avgTestReturn(avgTest)
                .degradation(avgTrain - avgTest)
                .avgTestSharpe(avgSharpe)
                .avgTestWinRate(avgWinRate)
                .totalTestTrades(totalTrades)
                .worstTestReturn(worstTest)
                .bestTestReturn(bestTest)
                .buyAndHoldReturn(buyAndHold)
                .build();

        log.info("Walk-forward {} complete: {} windows, train avg={}% test avg={}% degradation={}%",
                 stockSymbol, windows.size(),
                 String.format("%.2f", avgTrain),
                 String.format("%.2f", avgTest),
                 String.format("%.2f", avgTrain - avgTest));

        return result;
    }
}

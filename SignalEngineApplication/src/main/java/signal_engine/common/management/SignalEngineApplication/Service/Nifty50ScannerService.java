package signal_engine.common.management.SignalEngineApplication.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import signal_engine.common.management.SignalEngineApplication.model.BacktestResult;
import signal_engine.common.management.SignalEngineApplication.model.Candle;
import signal_engine.common.management.SignalEngineApplication.model.Nifty50ScanResult;

@Slf4j
@Service
@RequiredArgsConstructor
public class Nifty50ScannerService {

    private final MarketDataClient marketDataClient;
    private final BacktestService  backtestService;

    /**
     * Scan any NSE index and return backtest results ranked by return%.
     *
     * @param period   Data period: 1mo | 3mo | 6mo | 1y | 2y | 5y
     * @param exchange NS or BO
     * @param topN     If > 0, return only the top N. 0 = return all.
     * @param index    Index name: "NIFTY 50" | "NIFTY 100" | "NIFTY 200" | "NIFTY NEXT 50"
     */
    public List<Nifty50ScanResult> scanAll(String period, String exchange, int topN, String index) {

        // Fetch constituents for the requested index
        List<String> symbols = marketDataClient.fetchNifty50Symbols(index);
        log.info("Starting scan: index={} ({} symbols) period={}", index, symbols.size(), period);

        List<Nifty50ScanResult> results    = new ArrayList<>();
        AtomicInteger           done       = new AtomicInteger(0);

        for (String symbol : symbols) {
            log.info("[{}/{}] {}", done.incrementAndGet(), symbols.size(), symbol);
            results.add(runSingleStock(symbol, period, exchange));
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }

        // Sort: profitable first, errors last
        results.sort(Comparator
                .<Nifty50ScanResult>comparingInt(r -> r.isError() ? 1 : 0)
                .thenComparingDouble(r -> -r.getReturnPercent()));

        // Assign ranks
        int rank = 1;
        for (Nifty50ScanResult r : results) {
            r.setRank(r.isError() ? 0 : rank++);
        }

        log.info("Scan complete — {} ok, {} failed",
                results.stream().filter(r -> !r.isError()).count(),
                results.stream().filter(Nifty50ScanResult::isError).count());

        if (topN > 0) {
            return results.stream()
                    .filter(r -> !r.isError())
                    .limit(topN)
                    .collect(Collectors.toList());
        }
        return results;
    }

    /** Overload — defaults to Nifty 50 (backward compatible) */
    public List<Nifty50ScanResult> scanAll(String period, String exchange, int topN) {
        return scanAll(period, exchange, topN, "NIFTY 50");
    }

    private Nifty50ScanResult runSingleStock(String symbol, String period, String exchange) {
        try {
            List<Candle>   candles  = marketDataClient.fetchCandles(symbol, period, exchange);
            BacktestResult backtest = backtestService.run(symbol, candles);

            return Nifty50ScanResult.builder()
                    .symbol(symbol)
                    .returnPercent(backtest.getReturnPercent())
                    .winRate(backtest.getWinRate())
                    .sharpeRatio(backtest.getSharpeRatio())
                    .profitFactor(backtest.getProfitFactor())
                    .maxDrawdown(backtest.getMaxDrawdown())
                    .qualityScore(backtest.getQualityScore())
                    .totalTrades(backtest.getTotalTrades())
                    .totalProfit(backtest.getTotalProfit())
                    .finalCapital(backtest.getFinalCapital())
                    .assessment(backtest.getAssessment())
                    .error(false)
                    .build();

        } catch (Exception e) {
            log.warn("Failed {}: {}", symbol, e.getMessage());
            return Nifty50ScanResult.builder()
                    .symbol(symbol)
                    .error(true)
                    .errorMessage(e.getMessage())
                    .assessment("ERROR")
                    .build();
        }
    }
}
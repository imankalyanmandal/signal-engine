package signal_engine.common.management.SignalEngineApplication.model;

import java.util.List;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Builder
@Getter
@Setter
public class BacktestResult {

    // ── Core metrics ──────────────────────────────────────────────────────────
    private int    totalTrades;
    private double winRate;          // percentage 0-100
    private double finalCapital;
    private double totalProfit;
    private double returnPercent;
    private double maxDrawdown;      // percentage 0-100

    // ── Quality metrics ───────────────────────────────────────────────────────
    private double profitFactor;     // capped at 99.99 for valid JSON
    private double avgWinSize;
    private double avgLossSize;
    private double sharpeRatio;

    // ── Benchmark comparison ──────────────────────────────────────────────────
    private double benchmarkReturn;  // buy-and-hold return over same period

    // ── Trade list ────────────────────────────────────────────────────────────
    // Uses BacktestTrade (Layer 1 simulation) NOT the JPA Trade entity
    private List<BacktestTrade> trades;

    // ── profitFactor setter caps Infinity at 99.99 for valid JSON ─────────────
    public void setProfitFactor(double pf) {
        this.profitFactor = Double.isInfinite(pf) || Double.isNaN(pf) ? 99.99 : pf;
    }

    // ── Computed properties (not persisted, not in builder) ───────────────────

    /**
     * Alpha = strategy return minus buy-and-hold return.
     * Positive alpha means the strategy beats passive holding.
     */
    public double getAlpha() {
        return returnPercent - benchmarkReturn;
    }

    /**
     * Risk-Reward Ratio = average win / |average loss|.
     * Should be > 1.0 for a sustainable strategy.
     */
    public double getRiskRewardRatio() {
        if (avgLossSize == 0) return 99.99;
        return avgWinSize / Math.abs(avgLossSize);
    }

    /**
     * Expectancy per trade in currency.
     * (winRate/100 * avgWin) + ((1 - winRate/100) * avgLoss)
     * Positive = edge exists.
     */
    public double getExpectancyPerTrade() {
        double winProb = winRate / 100.0;
        return (winProb * avgWinSize) + ((1 - winProb) * avgLossSize);
    }

    /**
     * Quality score 0-10. Fixed from original (old version had broken RoR scaling).
     *
     * Components:
     *   Return percent  0-3 pts  (1% = 0.3pts, 10% = 3pts)
     *   Sharpe ratio    0-3 pts  (capped at 3)
     *   Profit factor   0-2 pts  (PF=2 gives full 2pts)
     *   Win rate bonus  0-1 pt   (only above 55%)
     *   Trade count penalty      (< 10 trades = unreliable data)
     */
    public double getQualityScore() {
        if (totalTrades == 0) return 0;

        double score = 0;

        // 1. Return percent (0-3 pts)
        if (returnPercent > 0) {
            score += Math.min(3.0, returnPercent * 0.3);
        }

        // 2. Sharpe ratio (0-3 pts)
        if (sharpeRatio > 0) {
            score += Math.min(3.0, sharpeRatio);
        }

        // 3. Profit factor (0-2 pts)
        if (profitFactor > 1.0) {
            score += Math.min(2.0, (profitFactor - 1.0) * 2.0);
        }

        // 4. Win rate bonus (0-1 pt) — only above 55%
        if (winRate > 55) {
            score += Math.min(1.0, (winRate - 55.0) / 20.0);
        }

        // 5. Penalty for too few trades (< 10 = statistically unreliable)
        if (totalTrades < 10) {
            score *= (totalTrades / 10.0);
        }

        return Math.min(10.0, score);
    }

    /**
     * Plain-English assessment of the strategy quality.
     */
    public String getAssessment() {
        if (totalTrades == 0)    return "NO TRADES — strategy generated no signals";
        if (totalTrades < 5)     return "INSUFFICIENT DATA — too few trades to assess";
        if (returnPercent < 0)   return "FAIL — strategy lost money";

        double q = getQualityScore();
        if (q < 3)  return "POOR — needs significant improvement";
        if (q < 5)  return "FAIR — acceptable but not exceptional";
        if (q < 7)  return "GOOD — solid strategy";
        return              "EXCELLENT — production-ready";
    }

    /**
     * Return on Risk: how much profit per unit of drawdown risk.
     * Used only for display — not included in quality score.
     */
    public double getReturnOnRisk() {
        if (maxDrawdown <= 0 || finalCapital <= 0) return 0;
        return totalProfit / (finalCapital * (maxDrawdown / 100.0));
    }
}
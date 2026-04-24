package signal_engine.common.management.SignalEngineApplication.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a single completed trade from the Layer 1 backtest simulation.
 *
 * Renamed from Trade → BacktestTrade to avoid conflict with the JPA
 * Trade entity used by the Layer 2 trade tracker (TradeService / TradeRepository).
 *
 * Used only inside BacktestService and BacktestResult — never persisted to DB.
 */
@Data
@Builder
public class BacktestTrade {

    private String  stock;
    private String  entryDate;
    private String  exitDate;
    private double  entryPrice;
    private double  exitPrice;
    private double  quantity;    // double here — fractional shares during position sizing
    private double  pnl;         // net P&L after commissions
    private boolean win;         // pnl > 0
    private String  exitReason;  // STOP_LOSS, TAKE_PROFIT, SELL_SIGNAL, END_OF_DATA
}
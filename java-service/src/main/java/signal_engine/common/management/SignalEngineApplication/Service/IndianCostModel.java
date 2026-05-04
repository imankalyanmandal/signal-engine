package signal_engine.common.management.SignalEngineApplication.Service;

import org.springframework.stereotype.Component;

/**
 * Realistic Indian equity-delivery cost model.
 *
 * Computes the round-trip cost of a single trade including ALL the charges
 * a retail Indian trader actually pays — not just a generic 0.1% fee.
 *
 * Default charges below are typical for a discount broker (Zerodha / Groww /
 * Upstox) on equity DELIVERY trades (not intraday). For intraday or F&O,
 * STT and exchange transaction charges differ.
 *
 * Charge breakdown for an equity-delivery round trip (buy + sell):
 *
 *   Per-trade brokerage   : flat ₹0 default (most discount brokers free on delivery)
 *   STT (sell side only)  : 0.1% on sell value
 *   Exchange txn charges  : NSE 0.00297% per leg
 *   GST                   : 18% on (brokerage + exchange + SEBI)
 *   SEBI charges          : 0.0001% per leg
 *   Stamp duty (buy only) : 0.015% on buy value
 *
 * For a ₹1,00,000 round trip: ~₹150 total cost (~0.15%).
 */
@Component
public class IndianCostModel {

    private static final double STT_RATE_SELL          = 0.001;
    private static final double NSE_TXN_RATE           = 0.0000297;
    private static final double GST_RATE               = 0.18;
    private static final double SEBI_RATE              = 0.000001;
    private static final double STAMP_DUTY_RATE_BUY    = 0.00015;
    private static final double BROKERAGE_PER_LEG_FLAT = 0.0;
    private static final double BROKERAGE_PER_LEG_PCT  = 0.0;

    /**
     * Total round-trip cost (buy + sell) for a single position.
     */
    public double roundTripCost(double entryPrice, double exitPrice, double quantity) {
        double buyValue  = entryPrice * quantity;
        double sellValue = exitPrice  * quantity;

        double buyBrokerage  = Math.min(BROKERAGE_PER_LEG_FLAT, buyValue  * BROKERAGE_PER_LEG_PCT);
        double sellBrokerage = Math.min(BROKERAGE_PER_LEG_FLAT, sellValue * BROKERAGE_PER_LEG_PCT);
        double brokerage     = buyBrokerage + sellBrokerage;

        double stt         = sellValue * STT_RATE_SELL;
        double exchangeTxn = (buyValue + sellValue) * NSE_TXN_RATE;
        double sebi        = (buyValue + sellValue) * SEBI_RATE;
        double gst         = (brokerage + exchangeTxn + sebi) * GST_RATE;
        double stampDuty   = buyValue * STAMP_DUTY_RATE_BUY;

        return brokerage + stt + exchangeTxn + sebi + gst + stampDuty;
    }

    public double roundTripCostPercent(double entryPrice, double exitPrice, double quantity) {
        double tradeValue = entryPrice * quantity;
        if (tradeValue == 0) return 0;
        return (roundTripCost(entryPrice, exitPrice, quantity) / tradeValue) * 100;
    }
}

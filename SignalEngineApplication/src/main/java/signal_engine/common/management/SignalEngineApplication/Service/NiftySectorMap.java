package signal_engine.common.management.SignalEngineApplication.Service;

import java.util.Map;

/**
 * Static sector mapping for NSE symbols.
 * Used by LiveScanService for sector deduplication —
 * limits output to 1 stock per sector to avoid concentrated bets.
 */
public class NiftySectorMap {

    private static final Map<String, String> SECTORS = Map.ofEntries(
        // Banking
        Map.entry("HDFCBANK",    "BANK"),
        Map.entry("ICICIBANK",   "BANK"),
        Map.entry("SBIN",        "BANK"),
        Map.entry("KOTAKBANK",   "BANK"),
        Map.entry("AXISBANK",    "BANK"),
        Map.entry("INDUSINDBK",  "BANK"),
        Map.entry("BANDHANBNK",  "BANK"),
        Map.entry("FEDERALBNK",  "BANK"),
        Map.entry("IDFCFIRSTB",  "BANK"),
        Map.entry("PNB",         "BANK"),
        Map.entry("BANKBARODA",  "BANK"),
        Map.entry("CANBK",       "BANK"),
        Map.entry("UNIONBANK",   "BANK"),

        // NBFC / Financial Services
        Map.entry("BAJFINANCE",  "NBFC"),
        Map.entry("BAJAJFINSV",  "NBFC"),
        Map.entry("SHRIRAMFIN",  "NBFC"),
        Map.entry("CHOLAFIN",    "NBFC"),
        Map.entry("MUTHOOTFIN",  "NBFC"),
        Map.entry("SBICARD",     "NBFC"),
        Map.entry("HDFCLIFE",    "INSURANCE"),
        Map.entry("SBILIFE",     "INSURANCE"),
        Map.entry("ICICIGI",     "INSURANCE"),
        Map.entry("LICI",        "INSURANCE"),

        // IT
        Map.entry("TCS",         "IT"),
        Map.entry("INFY",        "IT"),
        Map.entry("WIPRO",       "IT"),
        Map.entry("HCLTECH",     "IT"),
        Map.entry("TECHM",       "IT"),
        Map.entry("LTIM",        "IT"),
        Map.entry("MPHASIS",     "IT"),
        Map.entry("COFORGE",     "IT"),
        Map.entry("PERSISTENT",  "IT"),

        // Energy / Oil & Gas
        Map.entry("RELIANCE",    "ENERGY"),
        Map.entry("ONGC",        "ENERGY"),
        Map.entry("BPCL",        "ENERGY"),
        Map.entry("IOC",         "ENERGY"),
        Map.entry("COALINDIA",   "ENERGY"),
        Map.entry("POWERGRID",   "ENERGY"),
        Map.entry("NTPC",        "ENERGY"),
        Map.entry("ADANIGREEN",  "ENERGY"),
        Map.entry("TATAPOWER",   "ENERGY"),

        // FMCG
        Map.entry("HINDUNILVR",  "FMCG"),
        Map.entry("ITC",         "FMCG"),
        Map.entry("NESTLEIND",   "FMCG"),
        Map.entry("BRITANNIA",   "FMCG"),
        Map.entry("TATACONSUM",  "FMCG"),
        Map.entry("DABUR",       "FMCG"),
        Map.entry("MARICO",      "FMCG"),
        Map.entry("GODREJCP",    "FMCG"),
        Map.entry("COLPAL",      "FMCG"),

        // Auto
        Map.entry("MARUTI",      "AUTO"),
        Map.entry("TATAMOTORS",  "AUTO"),
        Map.entry("M&M",         "AUTO"),
        Map.entry("EICHERMOT",   "AUTO"),
        Map.entry("HEROMOTOCO",  "AUTO"),
        Map.entry("BAJAJ-AUTO",  "AUTO"),
        Map.entry("TVSMOTOR",    "AUTO"),
        Map.entry("ASHOKLEY",    "AUTO"),

        // Pharma
        Map.entry("SUNPHARMA",   "PHARMA"),
        Map.entry("DIVISLAB",    "PHARMA"),
        Map.entry("DRREDDY",     "PHARMA"),
        Map.entry("CIPLA",       "PHARMA"),
        Map.entry("APOLLOHOSP",  "PHARMA"),
        Map.entry("ALKEM",       "PHARMA"),
        Map.entry("TORNTPHARM", "PHARMA"),
        Map.entry("BIOCON",      "PHARMA"),

        // Metals
        Map.entry("JSWSTEEL",    "METAL"),
        Map.entry("TATASTEEL",   "METAL"),
        Map.entry("HINDALCO",    "METAL"),
        Map.entry("VEDL",        "METAL"),
        Map.entry("SAIL",        "METAL"),
        Map.entry("NATIONALUM",  "METAL"),

        // Cement
        Map.entry("ULTRACEMCO",  "CEMENT"),
        Map.entry("GRASIM",      "CEMENT"),
        Map.entry("AMBUJACEM",   "CEMENT"),
        Map.entry("ACC",         "CEMENT"),
        Map.entry("SHREECEM",    "CEMENT"),

        // Infra / Capital Goods
        Map.entry("LT",          "INFRA"),
        Map.entry("ADANIPORTS",  "INFRA"),
        Map.entry("ADANIENT",    "INFRA"),
        Map.entry("ABB",         "INFRA"),
        Map.entry("SIEMENS",     "INFRA"),
        Map.entry("BHEL",        "INFRA"),

        // Consumer / Retail
        Map.entry("TITAN",       "CONSUMER"),
        Map.entry("TRENT",       "CONSUMER"),
        Map.entry("DMART",       "CONSUMER"),
        Map.entry("NYKAA",       "CONSUMER"),
        Map.entry("ZOMATO",      "CONSUMER"),
        Map.entry("JUBLFOOD",    "CONSUMER"),

        // Telecom
        Map.entry("BHARTIARTL",  "TELECOM"),
        Map.entry("IDEA",        "TELECOM"),

        // Paints / Chemicals
        Map.entry("ASIANPAINT",  "PAINTS"),
        Map.entry("BERGEPAINT",  "PAINTS"),
        Map.entry("PIDILITIND",  "CHEMICALS"),
        Map.entry("SRF",         "CHEMICALS"),
        Map.entry("DEEPAKNTR",   "CHEMICALS")
    );

    /**
     * Returns the sector for a given symbol.
     * Falls back to "OTHER" for unmapped symbols.
     */
    public static String getSector(String symbol) {
        return SECTORS.getOrDefault(symbol.toUpperCase(), "OTHER");
    }
}

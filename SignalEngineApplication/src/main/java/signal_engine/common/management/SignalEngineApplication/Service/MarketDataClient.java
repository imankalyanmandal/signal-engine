package signal_engine.common.management.SignalEngineApplication.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import signal_engine.common.management.SignalEngineApplication.model.Candle;

@Service
@RequiredArgsConstructor
public class MarketDataClient {

    private final RestTemplate restTemplate;

    @Value("${market.data.service.url:http://localhost:5000}")
    private String dataServiceUrl;

    // ── Candle data ───────────────────────────────────────────────────────────

    /**
     * Fetch OHLCV candles from the Python market data service.
     *
     * @param symbol   NSE symbol e.g. HDFCBANK, TCS, INFY
     * @param period   5d | 1mo | 3mo | 6mo | 1y | 2y | 5y
     * @param exchange NS (NSE) or BO (BSE)
     */
    public List<Candle> fetchCandles(String symbol, String period, String exchange) {
        String url = String.format("%s/candles?symbol=%s&period=%s&exchange=%s",
                dataServiceUrl, symbol, period, exchange);

        try {
            ResponseEntity<List<Map<String, Object>>> response =
                    restTemplate.exchange(url, HttpMethod.GET, null,
                            new ParameterizedTypeReference<List<Map<String, Object>>>() {});

            List<Map<String, Object>> body = response.getBody();
            if (body == null || body.isEmpty()) {
                throw new RuntimeException("No candle data returned for: " + symbol);
            }

            return body.stream().map(m -> new Candle(
                    (String) m.get("date"),
                    toDouble(m.get("open")),
                    toDouble(m.get("high")),
                    toDouble(m.get("low")),
                    toDouble(m.get("close")),
                    toDouble(m.get("volume"))
            )).collect(Collectors.toList());

        } catch (HttpClientErrorException.NotFound e) {
            throw new RuntimeException("Symbol not found: " + symbol + "." + exchange);
        } catch (ResourceAccessException e) {
            throw new RuntimeException(
                    "Market data service is not running. Start with: python market_service.py", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch data for " + symbol + ": " + e.getMessage(), e);
        }
    }

    /** Convenience overload — defaults to NSE, 1 year */
    public List<Candle> fetchCandles(String symbol) {
        return fetchCandles(symbol, "1y", "NS");
    }

    // ── Current price ─────────────────────────────────────────────────────────

    /**
     * Fetch the latest close price for a stock.
     * Used by TradeService scheduler to refresh active trade prices every 5 min.
     *
     * Fetches 5-day candles (not 1d — avoids empty results on weekends/holidays)
     * and returns the most recent close.
     *
     * @param symbol NSE symbol e.g. HDFCBANK
     * @return latest close price
     */
    public double fetchCurrentPrice(String symbol) {
        List<Candle> candles = fetchCandles(symbol, "5d", "NS");
        // candles are in chronological order — last entry is the most recent
        return candles.get(candles.size() - 1).getClose();
    }

    // ── Symbol lists ──────────────────────────────────────────────────────────

    /**
     * Fetch the full Nifty 50 symbol list from the Python service.
     * Pass ?index=NIFTY+100 for broader universe.
     */
    @SuppressWarnings("unchecked")
    public List<String> fetchNifty50Symbols() {
        return fetchNifty50Symbols("NIFTY 50");
    }

    public List<String> fetchNifty50Symbols(String index) {
        String url = dataServiceUrl + "/nifty50/symbols?index="
                     + index.replace(" ", "+");

        try {
            ResponseEntity<Map<String, Object>> response =
                    restTemplate.exchange(url, HttpMethod.GET, null,
                            new ParameterizedTypeReference<Map<String, Object>>() {});

            Map<String, Object> body = response.getBody();
            if (body == null || !body.containsKey("symbols")) {
                throw new RuntimeException("Invalid response from /nifty50/symbols");
            }

            return (List<String>) body.get("symbols");

        } catch (ResourceAccessException e) {
            throw new RuntimeException(
                    "Market data service is not running. Start with: python market_service.py", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch Nifty 50 symbols: " + e.getMessage(), e);
        }
    }


    // ── Layer 2 — proxy to Python microservice ────────────────────────────────

    /**
     * Full LLM-powered Layer 2 analysis for a single stock.
     * Calls Python /layer2/analyse?symbol=TECHM
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchLayer2Analysis(String symbol) {
        String url = dataServiceUrl + "/layer2/analyse?symbol=" + symbol;
        try {
            ResponseEntity<Map<String, Object>> response =
                    restTemplate.exchange(url, HttpMethod.GET, null,
                            new ParameterizedTypeReference<Map<String, Object>>() {});
            Map<String, Object> body = response.getBody();
            if (body == null) throw new RuntimeException("Empty response from /layer2/analyse");
            return body;
        } catch (ResourceAccessException e) {
            throw new RuntimeException("Python service not running. Start with: python market_service.py", e);
        } catch (Exception e) {
            throw new RuntimeException("Layer 2 analysis failed for " + symbol + ": " + e.getMessage(), e);
        }
    }

    /**
     * Layer 2 scan for a comma-separated list of symbols.
     * Calls Python /layer2/scan?symbols=TECHM,HDFCBANK
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchLayer2Scan(String symbols) {
        String url = dataServiceUrl + "/layer2/scan?symbols=" + symbols;
        try {
            ResponseEntity<Map<String, Object>> response =
                    restTemplate.exchange(url, HttpMethod.GET, null,
                            new ParameterizedTypeReference<Map<String, Object>>() {});
            Map<String, Object> body = response.getBody();
            if (body == null) throw new RuntimeException("Empty response from /layer2/scan");
            return body;
        } catch (ResourceAccessException e) {
            throw new RuntimeException("Python service not running. Start with: python market_service.py", e);
        } catch (Exception e) {
            throw new RuntimeException("Layer 2 scan failed for [" + symbols + "]: " + e.getMessage(), e);
        }
    }

    /** Exposes the Python service URL for cache management endpoints. */
    public String getDataServiceUrl() { return dataServiceUrl; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        return Double.parseDouble(val.toString());
    }
}
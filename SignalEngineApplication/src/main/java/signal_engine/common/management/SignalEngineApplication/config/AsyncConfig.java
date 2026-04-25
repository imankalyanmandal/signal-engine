package signal_engine.common.management.SignalEngineApplication.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async and scheduling configuration.
 *
 * Enables:
 *   - @Async for background tasks
 *   - @Scheduled for price refresh and cache warming
 *   - Thread pool for parallel stock analysis in LiveScanService
 *   - RestTemplate bean required by MarketDataClient
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Value("${live.scan.thread.count:10}")
    private int scanThreadCount;

    /**
     * RestTemplate bean — required by MarketDataClient.
     * Must be defined here (or in main class) to avoid circular dependency.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Thread pool for parallel stock scanning.
     * Exposed as a bean so it can be injected into LiveScanService
     * and shut down gracefully on application stop.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService scanExecutorService() {
        return Executors.newFixedThreadPool(scanThreadCount);
    }
}

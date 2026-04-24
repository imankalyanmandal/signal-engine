package signal_engine.common.management.SignalEngineApplication.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import signal_engine.common.management.SignalEngineApplication.model.Trade;
import signal_engine.common.management.SignalEngineApplication.model.Trade.TradeStatus;

import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade, String> {

    List<Trade> findByStatusOrderByCreatedAtDesc(TradeStatus status);

    List<Trade> findBySymbolOrderByCreatedAtDesc(String symbol);

    List<Trade> findAllByOrderByCreatedAtDesc();

    boolean existsBySymbolAndStatus(String symbol, TradeStatus status);

    @Query("SELECT t FROM Trade t WHERE t.status = 'ACTIVE'")
    List<Trade> findAllActive();

    @Query("""
        SELECT COUNT(t) FROM Trade t
        WHERE t.status = 'CLOSED' AND t.exitPrice > t.entryPrice
        """)
    long countWinningTrades();

    @Query("SELECT COUNT(t) FROM Trade t WHERE t.status = 'CLOSED'")
    long countClosedTrades();
}

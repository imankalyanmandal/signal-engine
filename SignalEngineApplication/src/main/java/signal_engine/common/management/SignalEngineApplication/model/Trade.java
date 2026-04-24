package signal_engine.common.management.SignalEngineApplication.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persisted trade record — lifecycle: SIGNAL → ACTIVE → CLOSED
 */
@Entity
@Table(name = "trades")
@Data
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // ── Stock identity ────────────────────────────────────────────────────────
    @Column(nullable = false)
    private String symbol;

    private String companyName;

    // ── Layer 2 signal data ───────────────────────────────────────────────────
    private Integer compositeScore;
    private String  swingVerdict;       // STRONG_BUY, BUY, HOLD, AVOID
    private String  conviction;         // HIGH, MEDIUM, LOW
    private String  rationale;
    private String  entryNote;

    // ── Entry zone ────────────────────────────────────────────────────────────
    private Double  entryLow;
    private Double  entryHigh;
    private Double  stopLoss;
    private Double  target;
    private Integer maxHoldDays;        // default 20 for swing trades

    // ── Actual trade ──────────────────────────────────────────────────────────
    private Double        entryPrice;
    private Integer       quantity;
    private LocalDate     entryDate;
    private Double        currentPrice;
    private LocalDateTime lastPriceUpdate;

    // ── Exit ──────────────────────────────────────────────────────────────────
    private Double    exitPrice;
    private LocalDate exitDate;
    private String    exitReason;       // TARGET_HIT, STOP_HIT, TIME_LIMIT, MANUAL, TRAIL_STOP

    // ── Status ────────────────────────────────────────────────────────────────
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TradeStatus status = TradeStatus.SIGNAL;

    // ── Timestamps ────────────────────────────────────────────────────────────
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt   = LocalDateTime.now();
        updatedAt   = LocalDateTime.now();
        if (maxHoldDays == null) maxHoldDays = 20;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Computed fields — @Transient means NOT persisted to DB ────────────────

    /** P&L as a percentage of entry price. Null if trade not yet entered. */
    @Transient
    public Double getPnlPct() {
        if (entryPrice == null || currentPrice == null) return null;
        return ((currentPrice - entryPrice) / entryPrice) * 100;
    }

    /** Absolute P&L in rupees (price diff × quantity). */
    @Transient
    public Double getPnlAbs() {
        if (entryPrice == null || currentPrice == null || quantity == null) return null;
        return (currentPrice - entryPrice) * quantity;
    }

    /** Calendar days held (entry to today, or entry to exit if closed). */
    @Transient
    public Integer getDaysHeld() {
        if (entryDate == null) return null;
        LocalDate end = (exitDate != null) ? exitDate : LocalDate.now();
        return (int) java.time.temporal.ChronoUnit.DAYS.between(entryDate, end);
    }

    /** Days remaining in the max holding window. 0 = time limit reached. */
    @Transient
    public Integer getDaysRemaining() {
        if (entryDate == null || maxHoldDays == null) return null;
        return Math.max(0, maxHoldDays - getDaysHeld());
    }

    /**
     * Returns an exit alert code if any rule is triggered, null otherwise.
     *   STOP_HIT     — price breached stop loss
     *   TARGET_HIT   — price reached target
     *   TIME_LIMIT   — max hold days reached
     *   TIME_WARNING — within 3 days of time limit
     */
    @Transient
    public String getExitAlert() {
        if (status != TradeStatus.ACTIVE) return null;
        if (currentPrice != null && stopLoss != null && currentPrice <= stopLoss)
            return "STOP_HIT";
        if (currentPrice != null && target != null && currentPrice >= target)
            return "TARGET_HIT";
        Integer remaining = getDaysRemaining();
        if (remaining != null && remaining == 0)  return "TIME_LIMIT";
        if (remaining != null && remaining <= 3)  return "TIME_WARNING";
        return null;
    }

    // ── Status enum ───────────────────────────────────────────────────────────

    public enum TradeStatus {
        SIGNAL,   // Layer 2 passed — waiting for trader to enter
        ACTIVE,   // Trade entered — tracking live
        CLOSED    // Trade exited — moved to history
    }
}
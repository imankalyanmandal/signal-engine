package signal_engine.common.management.SignalEngineApplication.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

/**
 * SystemLifecycleListener — sends email notifications on startup and shutdown.
 *
 * ApplicationReadyEvent  — fires after Spring Boot is fully started and ready
 *                           to accept requests. More reliable than @PostConstruct
 *                           since all beans including email are fully wired.
 *
 * ContextClosedEvent     — fires when the Spring context is being shut down.
 *                           Triggered by: docker compose down, Ctrl+C, system restart.
 *                           Note: on a hard kill (kill -9) this may not fire.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemLifecycleListener {

    private final EmailService emailService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("[Lifecycle] System started — sending startup notification");
        emailService.sendStartupEmail();
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        log.info("[Lifecycle] System shutting down — sending shutdown notification");
        emailService.sendShutdownEmail();
    }
}

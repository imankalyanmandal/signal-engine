package signal_engine.common.management.SignalEngineApplication.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import signal_engine.common.management.SignalEngineApplication.model.LiveScanResult;

/**
 * EmailService — sends HTML email alerts when Signal Engine finds setups.
 *
 * Uses Gmail SMTP with App Password (not your real password).
 * How to get App Password:
 *   1. Gmail → Settings → Security → 2-Step Verification → App passwords
 *   2. Create one for "Signal Engine"
 *   3. Add to application.properties: email.password=xxxx xxxx xxxx xxxx
 *
 * Required properties:
 *   email.enabled=true
 *   email.from=youremail@gmail.com
 *   email.to=youremail@gmail.com
 *   email.password=your_app_password
 *   email.smtp.host=smtp.gmail.com
 *   email.smtp.port=587
 */
@Slf4j
@Service
public class EmailService {

    @Value("${email.enabled:false}")
    private boolean enabled;

    @Value("${email.from:}")
    private String from;

    @Value("${email.to:}")
    private String to;

    @Value("${email.password:}")
    private String password;

    @Value("${email.smtp.host:smtp.gmail.com}")
    private String smtpHost;

    @Value("${email.smtp.port:587}")
    private String smtpPort;

    /**
     * Send scan results email.
     * Called only when at least one setup is found.
     */
    public void sendScanResults(
            List<LiveScanResult> strictSetups,
            List<LiveScanResult> moderateSetups,
            String marketRegime
    ) {
        if (!enabled) {
            log.info("[Email] Disabled — skipping alert");
            return;
        }
        if (from.isBlank() || to.isBlank() || password.isBlank()) {
            log.warn("[Email] Missing config — set email.from, email.to, email.password");
            return;
        }

        try {
            String subject = buildSubject(strictSetups, moderateSetups);
            String body    = buildHtml(strictSetups, moderateSetups, marketRegime);
            send(subject, body);
            log.info("[Email] Sent to {} — {} strict, {} moderate setups",
                    to, strictSetups.size(), moderateSetups.size());
        } catch (Exception e) {
            log.error("[Email] Failed to send: {}", e.getMessage());
        }
    }

    // ── System lifecycle emails ───────────────────────────────────────────────

    /**
     * Sent when Spring Boot application starts successfully.
     * Called from SystemLifecycleListener on ApplicationReadyEvent.
     */
    public void sendStartupEmail() {
        if (!enabled || from.isBlank() || to.isBlank() || password.isBlank()) return;
        try {
            String time = java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss"));
            String subject = "[SIGNAL-ENGINE UPDATE] ✅ System started — " + time;
            String body = buildSystemEmail(
                "✅ Signal Engine Started",
                "#4ade80",
                time,
                "The Signal Engine system has started successfully.",
                new String[][]{
                    {"Status",    "Running"},
                    {"Java API",  "http://localhost:8080"},
                    {"Python",    "http://localhost:5000/health"},
                    {"Daily scan","Every day at 9:00 PM IST"},
                    {"Indices",   "Nifty 50 → Next 50 → Midcap 100 → Midcap 150 → Smallcap 250"},
                }
            );
            send(subject, body);
            log.info("[Email] Startup notification sent");
        } catch (Exception e) {
            log.warn("[Email] Startup email failed: {}", e.getMessage());
        }
    }

    /**
     * Sent when Spring Boot application is shutting down.
     * Called from SystemLifecycleListener on ContextClosedEvent.
     */
    public void sendShutdownEmail() {
        if (!enabled || from.isBlank() || to.isBlank() || password.isBlank()) return;
        try {
            String time = java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss"));
            String subject = "[SIGNAL-ENGINE UPDATE] 🔴 System stopped — " + time;
            String body = buildSystemEmail(
                "🔴 Signal Engine Stopped",
                "#f87171",
                time,
                "The Signal Engine system has been shut down.",
                new String[][]{
                    {"Status", "Offline"},
                    {"Time",   time},
                    {"Note",   "Daily scans will not run until the system is restarted."},
                }
            );
            send(subject, body);
            log.info("[Email] Shutdown notification sent");
        } catch (Exception e) {
            log.warn("[Email] Shutdown email failed: {}", e.getMessage());
        }
    }

    private String buildSystemEmail(String title, String color, String time,
                                     String message, String[][] details) {
        StringBuilder rows = new StringBuilder();
        for (String[] row : details) {
            rows.append(String.format(
                "<tr><td style='padding:6px 12px;color:#8a9188;font-family:monospace;font-size:12px'>%s</td>" +
                "<td style='padding:6px 12px;color:#e8ebe6;font-size:12px'>%s</td></tr>",
                row[0], row[1]));
        }
        return String.format("""
            <!DOCTYPE html><html><head><meta charset="UTF-8"></head>
            <body style="font-family:-apple-system,Arial,sans-serif;background:#0d0f0e;
                         color:#e8ebe6;margin:0;padding:20px">
              <div style="max-width:520px;margin:0 auto">
                <div style="background:#141614;border:1px solid #232623;border-radius:8px;
                            padding:20px;margin-bottom:16px;border-left:3px solid %s">
                  <div style="font-size:18px;font-weight:600;color:%s;margin-bottom:4px">%s</div>
                  <div style="font-size:11px;color:#8a9188;font-family:monospace">%s</div>
                </div>
                <div style="background:#141614;border:1px solid #232623;border-radius:8px;padding:16px">
                  <p style="color:#8a9188;font-size:13px;margin:0 0 12px">%s</p>
                  <table style="width:100%%;border-collapse:collapse">%s</table>
                </div>
                <div style="font-size:11px;color:#545c52;font-family:monospace;
                            text-align:center;margin-top:16px">
                  Signal Engine · This is an automated system notification
                </div>
              </div>
            </body></html>
            """, color, color, title, time, message, rows.toString());
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void send(String subject, String htmlBody) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.auth",            "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host",            smtpHost);
        props.put("mail.smtp.port",            smtpPort);
        props.put("mail.smtp.ssl.trust",       smtpHost);

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, password);
            }
        });

        Message msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        msg.setSubject(subject);
        msg.setContent(htmlBody, "text/html; charset=utf-8");

        Transport.send(msg);
    }

    private String buildSubject(List<LiveScanResult> strict, List<LiveScanResult> moderate) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        if (!strict.isEmpty()) {
            return String.format("[SIGNAL-ENGINE UPDATE] 🚨 %d HIGH CONVICTION setup(s) found — %s",
                    strict.size(), date);
        }
        long total = strict.size() + moderate.stream()
                .filter(m -> strict.stream().noneMatch(s -> s.getSymbol().equals(m.getSymbol())))
                .count();
        if (total > 0) {
            return String.format("[SIGNAL-ENGINE UPDATE] 📊 %d setup(s) found — %s", total, date);
        }
        return String.format("[SIGNAL-ENGINE UPDATE] 📋 Daily scan complete — no setups today — %s", date);
    }

    private String buildHtml(
            List<LiveScanResult> strict,
            List<LiveScanResult> moderate,
            String regime
    ) {
        StringBuilder sb = new StringBuilder();
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));

        sb.append("""
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
              body { font-family: -apple-system, Arial, sans-serif; background: #0d0f0e;
                     color: #e8ebe6; margin: 0; padding: 20px; }
              .container { max-width: 600px; margin: 0 auto; }
              .header { background: #141614; border: 1px solid #232623;
                        border-radius: 8px; padding: 20px; margin-bottom: 16px; }
              .title { font-size: 20px; font-weight: 600; color: #4ade80; margin: 0 0 4px; }
              .sub   { font-size: 12px; color: #8a9188; font-family: monospace; }
              .regime { display: inline-block; padding: 4px 12px; border-radius: 20px;
                        font-size: 11px; font-family: monospace; margin-top: 8px; }
              .regime-bull  { background: rgba(74,222,128,.15); color: #4ade80; }
              .regime-side  { background: rgba(251,191,36,.15);  color: #fbbf24; }
              .regime-bear  { background: rgba(248,113,113,.15); color: #f87171; }
              .section-title { font-size: 11px; text-transform: uppercase;
                               letter-spacing: .1em; color: #545c52; margin: 16px 0 8px; }
              .card { background: #141614; border: 1px solid #232623;
                      border-radius: 8px; padding: 14px 16px; margin-bottom: 10px; }
              .card.high { border-left: 3px solid #4ade80; }
              .card.medium { border-left: 3px solid #60a5fa; }
              .card.low  { border-left: 3px solid #545c52; }
              .sym  { font-size: 18px; font-weight: 600; }
              .meta { font-size: 11px; color: #8a9188; font-family: monospace; margin-top: 2px; }
              .badge { display: inline-block; font-size: 10px; padding: 2px 8px;
                       border-radius: 20px; font-family: monospace; margin: 4px 4px 0 0; }
              .b-green  { background: rgba(74,222,128,.1);  color: #4ade80; }
              .b-blue   { background: rgba(96,165,250,.1);  color: #60a5fa; }
              .b-gray   { background: #1c1f1c; color: #8a9188; }
              .grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; margin-top: 10px; }
              .metric { background: #1c1f1c; border-radius: 6px; padding: 8px 10px; }
              .mlabel { font-size: 10px; color: #545c52; text-transform: uppercase;
                        letter-spacing: .08em; font-family: monospace; }
              .mval   { font-size: 14px; font-weight: 600; margin-top: 2px; }
              .pos { color: #4ade80; } .neg { color: #f87171; } .warn { color: #fbbf24; }
              .footer { font-size: 11px; color: #545c52; font-family: monospace;
                        text-align: center; margin-top: 20px; }
              .no-setups { text-align: center; padding: 20px; color: #545c52;
                           font-family: monospace; font-size: 12px; }
            </style>
            </head>
            <body>
            <div class="container">
            """);

        // Header
        String regimeClass = regime != null && regime.contains("BULL") ? "regime-bull"
                           : regime != null && regime.contains("BEAR") ? "regime-bear"
                           : "regime-side";

        sb.append(String.format("""
            <div class="header">
              <div class="title">Signal Engine</div>
              <div class="sub">Daily scan — %s</div>
              <div class="regime %s">Market: %s</div>
            </div>
            """, date, regimeClass, regime != null ? regime : "UNKNOWN"));

        // Strict setups
        sb.append("<div class='section-title'>🚨 Strict setups (high conviction)</div>");
        if (strict.isEmpty()) {
            sb.append("<div class='no-setups'>No strict setups today</div>");
        } else {
            for (LiveScanResult r : strict) {
                sb.append(buildCard(r));
            }
        }

        // Moderate setups (exclude those already in strict)
        List<LiveScanResult> moderateOnly = moderate.stream()
                .filter(m -> strict.stream().noneMatch(s -> s.getSymbol().equals(m.getSymbol())))
                .toList();

        sb.append("<div class='section-title'>📊 Moderate setups</div>");
        if (moderateOnly.isEmpty()) {
            sb.append("<div class='no-setups'>No additional moderate setups today</div>");
        } else {
            for (LiveScanResult r : moderateOnly) {
                sb.append(buildCard(r));
            }
        }

        sb.append("""
            <div class="footer">
              Signal Engine · Layer 2 + Layer 3 analysis available in the trade tracker<br>
              This is not financial advice. Always do your own research.
            </div>
            </div></body></html>
            """);

        return sb.toString();
    }

    private String buildCard(LiveScanResult r) {
        String convClass = "HIGH".equals(r.getConviction()) ? "high"
                         : "MEDIUM".equals(r.getConviction()) ? "medium" : "low";
        String rsiColor  = r.getRsi() < 45 ? "pos" : r.getRsi() > 60 ? "warn" : "";

        return String.format("""
            <div class="card %s">
              <div class="sym">%s</div>
              <div class="meta">%s · Score %d/100</div>
              <div style="margin-top:6px">
                <span class="badge b-green">%s</span>
                <span class="badge b-blue">%s</span>
                <span class="badge b-gray">%s</span>
              </div>
              <div class="grid">
                <div class="metric">
                  <div class="mlabel">RSI</div>
                  <div class="mval %s">%.1f</div>
                </div>
                <div class="metric">
                  <div class="mlabel">Entry</div>
                  <div class="mval">₹%.0f–%.0f</div>
                </div>
                <div class="metric">
                  <div class="mlabel">Stop</div>
                  <div class="mval neg">₹%.0f</div>
                </div>
                <div class="metric">
                  <div class="mlabel">Target</div>
                  <div class="mval pos">₹%.0f</div>
                </div>
                <div class="metric">
                  <div class="mlabel">Risk</div>
                  <div class="mval warn">%.1f%%</div>
                </div>
                <div class="metric">
                  <div class="mlabel">R:R</div>
                  <div class="mval pos">%.1fx</div>
                </div>
              </div>
            </div>
            """,
            convClass,
            r.getSymbol(),
            r.getSector() != null ? r.getSector() : "",
            r.getSetupScore(),
            r.getVerdict(),
            r.getConviction(),
            r.getSetupType() != null ? r.getSetupType() : "",
            rsiColor, r.getRsi(),
            r.getEntryLow(), r.getEntryHigh(),
            r.getStopLoss(),
            r.getTarget(),
            r.getRiskPct(),
            r.getRrRatio()
        );
    }
}
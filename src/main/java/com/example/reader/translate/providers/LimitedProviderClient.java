package com.example.reader.translate.providers;

import com.example.reader.translate.AdminTranslationContext;
import com.example.reader.translate.properties.TranslateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class LimitedProviderClient implements ProviderClient {
    private static final Logger log = LoggerFactory.getLogger(LimitedProviderClient.class);
    private static final DateTimeFormatter RESET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private final ProviderClient delegate;
    private final ProviderClient fallback;
    private final TranslateLimitProperties limits;
    private final ZoneId zone;
    private final ConcurrentHashMap<String, AtomicInteger> ipCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> globalCounts = new ConcurrentHashMap<>();
    private volatile LocalDate activeDate;

    public LimitedProviderClient(ProviderClient delegate, TranslateLimitProperties limits) {
        this.delegate = delegate;
        this.fallback = new StubProviderClient();
        this.limits = limits;
        this.zone = ZoneId.systemDefault();
        this.activeDate = LocalDate.now(zone);
    }

    @Override
    public LiteraryModelCatalog literaryModels() {
        return delegate.literaryModels();
    }

    @Override
    public String literal(String text, String source, String target) {
        QuotaDecision decision = reserve(text, "literal");
        if (!decision.allowed()) {
            return quotaFallback(decision, fallback.literal(text, source, target));
        }
        try {
            return delegate.literal(text, source, target);
        } catch (Exception e) {
            return providerFailureFallback(e, fallback.literal(text, source, target));
        }
    }

    @Override
    public String literary(String text, String source, String target, String bookContext) {
        return literary(text, source, target, bookContext, null);
    }

    @Override
    public String literary(String text, String source, String target, String bookContext, String modelOverride) {
        QuotaDecision decision = reserve(text, "literary");
        if (!decision.allowed()) {
            if (AdminTranslationContext.isActive()) {
                throw new IllegalStateException(decision.message());
            }
            return quotaFallback(decision, fallback.literary(text, source, target, bookContext));
        }
        try {
            return delegate.literary(text, source, target, bookContext, modelOverride);
        } catch (Exception e) {
            if (AdminTranslationContext.isActive()) {
                throw e;
            }
            return providerFailureFallback(e, fallback.literary(text, source, target, bookContext));
        }
    }

    @Override
    public String explain(String text, String source, String target, String bookContext) {
        QuotaDecision decision = reserve(text, "explain");
        if (!decision.allowed()) {
            return quotaFallback(decision, fallback.explain(text, source, target, bookContext));
        }
        try {
            return delegate.explain(text, source, target, bookContext);
        } catch (Exception e) {
            return providerFailureFallback(e, fallback.explain(text, source, target, bookContext));
        }
    }

    @Override
    public String cleanOcr(String text, String language, String modelOverride) {
        if (!AdminTranslationContext.isActive()) {
            throw new IllegalStateException("OCR cleanup is available only during admin ingestion");
        }
        return delegate.cleanOcr(text, language, modelOverride);
    }

    @Override
    public String cleanOcr(String text, byte[] pageImagePng, String language, String modelOverride) {
        if (!AdminTranslationContext.isActive()) {
            throw new IllegalStateException("OCR cleanup is available only during admin ingestion");
        }
        return delegate.cleanOcr(text, pageImagePng, language, modelOverride);
    }

    private QuotaDecision reserve(String text, String action) {
        if (!limits.isEnabled()) {
            return QuotaDecision.permitted();
        }

        int maxTextChars = limits.maxTextCharsFor(action);
        if (maxTextChars > 0 && text != null && text.length() > maxTextChars) {
            log.info("Translate {} rejected by max text length: {} > {}", action, text.length(), maxTextChars);
            return QuotaDecision.rejected(
                    "The selected text is too long for AI mode. Select a shorter fragment."
            );
        }

        if (isAdminRequest()) {
            return QuotaDecision.permitted();
        }

        synchronized (this) {
            resetIfNeeded();

            String ip = clientIp();
            int perIpDaily = limits.perIpDailyFor(action);
            AtomicInteger ipCount = ipCounts.computeIfAbsent(action + "|" + ip, ignored -> new AtomicInteger(0));
            if (perIpDaily >= 0 && ipCount.get() >= perIpDaily) {
                log.info("Translate {} rejected by per-IP quota: ip={} count={} limit={}", action, ip, ipCount.get(), perIpDaily);
                return QuotaDecision.rejected(
                        "Daily AI limit reached for this visitor. AI mode resets at " + nextResetText() + "."
                );
            }

            int globalDaily = limits.globalDailyFor(action);
            AtomicInteger globalCount = globalCounts.computeIfAbsent(action, ignored -> new AtomicInteger(0));
            if (globalDaily >= 0 && globalCount.get() >= globalDaily) {
                log.info("Translate {} rejected by global quota: count={} limit={}", action, globalCount.get(), globalDaily);
                return QuotaDecision.rejected(
                        "The public demo reached its daily AI limit. AI mode resets at " + nextResetText() + "."
                );
            }

            ipCount.incrementAndGet();
            globalCount.incrementAndGet();
            return QuotaDecision.permitted();
        }
    }

    private String quotaFallback(QuotaDecision decision, String fallbackText) {
        return decision.message() + " Showing demo stub output instead.\n\n" + fallbackText;
    }

    private String providerFailureFallback(Exception error, String fallbackText) {
        log.warn("Paid translation provider failed; returning stub fallback: {}", error.toString());
        return "AI provider is temporarily unavailable or too slow. Showing demo stub output instead.\n\n" + fallbackText;
    }

    private void resetIfNeeded() {
        LocalDate today = LocalDate.now(zone);
        if (today.equals(activeDate)) {
            return;
        }
        synchronized (this) {
            if (today.equals(activeDate)) {
                return;
            }
            activeDate = today;
            ipCounts.clear();
            globalCounts.clear();
            log.info("Translate daily quotas reset for {}", today);
        }
    }

    private String nextResetText() {
        LocalDateTime reset = activeDate.plusDays(1).atStartOfDay();
        return reset.atZone(zone).format(RESET_FORMAT);
    }

    private String clientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isAdminRequest() {
        if (AdminTranslationContext.isActive()) {
            return true;
        }
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        String uri = attributes.getRequest().getRequestURI();
        return uri != null && uri.startsWith("/api/admin/");
    }

    private record QuotaDecision(boolean allowed, String message) {
        static QuotaDecision permitted() {
            return new QuotaDecision(true, "");
        }

        static QuotaDecision rejected(String message) {
            return new QuotaDecision(false, message);
        }
    }
}

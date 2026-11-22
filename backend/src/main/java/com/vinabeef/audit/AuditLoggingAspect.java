package com.vinabeef.audit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditLoggingAspect {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Around("@annotation(com.vinabeef.audit.Audited)")
    public Object captureAudit(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Audited auditAnnotation = method.getAnnotation(Audited.class);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actorId = (auth != null && auth.isAuthenticated()) ? auth.getName() : "ANONYMOUS";
        String actorRole = (auth != null && !auth.getAuthorities().isEmpty()) 
                ? auth.getAuthorities().iterator().next().getAuthority() : "NONE";

        String clientIp = "UNKNOWN";
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            clientIp = req.getRemoteAddr();
        }

        String outcome = "SUCCESS";
        String errorMessage = null;
        Object result;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable t) {
            outcome = t.getClass().getSimpleName().contains("AccessDenied") ? "DENIED" : "FAILED";
            errorMessage = t.getMessage();
            throw t;
        } finally {
            try {
                String auditId = UUID.randomUUID().toString();
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("method", method.getName());
                details.put("executionTimeMs", System.currentTimeMillis() - startTime);
                details.put("clientIp", clientIp);
                details.put("arguments", AuditPayload.sanitize(joinPoint.getArgs(), this::beanToMap));
                if (errorMessage != null) {
                    details.put("error", errorMessage);
                }

                String detailsJson = objectMapper.writeValueAsString(details);

                jdbcTemplate.update("""
                    INSERT INTO audit_entries (audit_id, actor_id, action, target, outcome, details, event_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, auditId, actorId, auditAnnotation.action(), auditAnnotation.targetType(), outcome, detailsJson,
                        Timestamp.from(Instant.now()));   // pgjdbc cannot bind java.time.Instant: that made every insert fail

                log.info("[AUDIT] Actor: '{}' [{}] | Action: '{}' on '{}' | Outcome: {}",
                        actorId, actorRole, auditAnnotation.action(), auditAnnotation.targetType(), outcome);
            } catch (Exception ex) {
                log.error("Failed to persist audit log entry for {} by {}: {}", auditAnnotation.action(), actorId,
                        rootMessage(ex), ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> beanToMap(Object bean) {
        return objectMapper.convertValue(bean, Map.class);
    }

    /** The log used to stop at "bad SQL grammar" and hide the driver's reason. */
    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
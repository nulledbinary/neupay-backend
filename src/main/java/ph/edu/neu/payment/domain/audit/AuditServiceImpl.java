package ph.edu.neu.payment.domain.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ph.edu.neu.payment.config.AppProperties;

import java.util.UUID;

@Service
public class AuditServiceImpl implements AuditService {

    private final AuditLogRepository repo;
    private final AppProperties props;

    public AuditServiceImpl(AuditLogRepository repo, AppProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorUserId, String action, String entityType, String entityId, String details) {
        if (!props.audit().enabled()) return;

        String ip = null;
        String ua = null;
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            HttpServletRequest req = sra.getRequest();
            ip = clientIp(req);
            ua = req.getHeader("User-Agent");
            if (ua != null && ua.length() > 255) ua = ua.substring(0, 255);
        }

        repo.save(new AuditLog(actorUserId, action, entityType, entityId, ip, ua, details));
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return req.getRemoteAddr();
    }
}

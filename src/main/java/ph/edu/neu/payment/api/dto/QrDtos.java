package ph.edu.neu.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import ph.edu.neu.payment.domain.qr.QrMode;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class QrDtos {

    private QrDtos() {}

    public record IssueRequest(@NotNull QrMode mode) {}

    public record IssueResponse(
            String token,
            QrMode mode,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            int rotationSeconds) {}

    public record RedeemRequest(@NotBlank String token) {}

    public record RedeemResponse(
            UUID userId,
            String userFullName,
            String idNumber,
            QrMode mode,
            String tokenReference) {}

    /** Non-consuming preview used by the iOS scanner before the user confirms an amount. */
    public record PreviewRequest(@NotBlank String token) {}

    public record PreviewResponse(
            UUID userId,
            String userFullName,
            String idNumber,
            QrMode mode,
            OffsetDateTime expiresAt) {}
}

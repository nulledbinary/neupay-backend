package ph.edu.neu.payment.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import ph.edu.neu.payment.domain.transaction.TransactionCategory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {}

    public record InitiatePaymentRequest(
            @NotBlank String qrToken,
            @NotNull @DecimalMin(value = "0.01", inclusive = true)
            @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 160) String title,
            @NotNull TransactionCategory category) {}

    public record TopUpRequest(
            @NotBlank String qrToken,
            @NotNull @DecimalMin(value = "0.01", inclusive = true)
            @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 160) String note) {}

    /** Peer-to-peer pay: caller's wallet is debited, the QR holder's wallet is credited. */
    public record PeerPayRequest(
            @NotBlank String qrToken,
            @NotNull @DecimalMin(value = "0.01", inclusive = true)
            @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 160) String note) {}

    public record AdminTopUpRequest(
            @NotNull UUID userId,
            @NotNull @DecimalMin(value = "0.01", inclusive = true)
            @Digits(integer = 13, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 160) String note) {}

    public record TransactionResult(
            UUID transactionId,
            String reference,
            BigDecimal amount,
            BigDecimal balanceAfter,
            OffsetDateTime occurredAt) {}
}

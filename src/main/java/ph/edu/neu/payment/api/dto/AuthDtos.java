package ph.edu.neu.payment.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import ph.edu.neu.payment.domain.user.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(max = 160) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 6, max = 32) @Pattern(regexp = "[A-Za-z0-9-]+") String idNumber,
            @Size(max = 160) String program,
            @NotBlank @Size(min = 12, max = 128) String password) {}

    /**
     * Login is by ID number only — the {@code email} legacy field is no
     * longer accepted. iOS and the dashboard both send {@code idNumber}.
     */
    public record LoginRequest(
            @NotBlank @Size(min = 6, max = 32) String idNumber,
            @NotBlank String password) {}

    public record RefreshRequest(
            @NotBlank String refreshToken) {}

    public record AuthResponse(
            String accessToken,
            OffsetDateTime accessTokenExpiresAt,
            String refreshToken,
            OffsetDateTime refreshTokenExpiresAt,
            ProfileSummary user) {}

    public record StepUpResponse(
            String accessToken,
            OffsetDateTime expiresAt) {}

    /**
     * Web cashier step-up: re-confirm the password to unlock sensitive
     * operations like direct wallet top-up. Returns a short-lived step-up
     * JWT the client must send as the bearer for the next privileged call.
     */
    public record PasswordStepUpRequest(
            @NotBlank String password) {}

    public record ProfileSummary(
            UUID id,
            String fullName,
            String email,
            String idNumber,
            String program,
            UserRole role,
            String status) {}
}

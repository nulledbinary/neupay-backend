package ph.edu.neu.payment.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.neu.payment.api.dto.AuthDtos;
import ph.edu.neu.payment.common.error.ConflictException;
import ph.edu.neu.payment.common.error.UnauthorizedException;
import ph.edu.neu.payment.config.AppProperties;
import ph.edu.neu.payment.domain.audit.AuditService;
import ph.edu.neu.payment.domain.user.User;
import ph.edu.neu.payment.domain.user.UserRepository;
import ph.edu.neu.payment.domain.user.UserRole;
import ph.edu.neu.payment.domain.user.VerificationStatus;
import ph.edu.neu.payment.domain.wallet.Wallet;
import ph.edu.neu.payment.domain.wallet.WalletProvisioner;
import ph.edu.neu.payment.domain.wallet.WalletRepository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository users;
    private final WalletRepository wallets;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    private final AppProperties props;

    public AuthServiceImpl(UserRepository users,
                           WalletRepository wallets,
                           RefreshTokenRepository refreshTokens,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           AuditService audit,
                           AppProperties props) {
        this.users = users;
        this.wallets = wallets;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.props = props;
    }

    @Override
    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest req) {
        if (users.existsByEmailIgnoreCase(req.email()))
            throw new ConflictException("Email already registered");
        if (users.existsByIdNumber(req.idNumber()))
            throw new ConflictException("ID number already registered");

        User u = new User(
                req.fullName(),
                req.email().toLowerCase(),
                req.idNumber(),
                req.program(),
                UserRole.STUDENT,
                VerificationStatus.PENDING,
                passwordEncoder.encode(req.password()));
        users.save(u);

        // Provision a wallet for the new user.
        Wallet w = new Wallet(u,
                WalletProvisioner.cardNumberFor(req.idNumber()),
                OffsetDateTime.now().getYear() + 4);
        wallets.save(w);

        audit.record(u.getId(), "USER_REGISTER", "User", u.getId().toString(), null);

        return mintAuthResponse(u, null);
    }

    @Override
    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest req, String deviceId) {
        String principal = req.idNumber().trim();
        User u = users.findByIdNumber(principal)
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        if (!passwordEncoder.matches(req.password(), u.getPasswordHash()))
            throw new UnauthorizedException("Invalid credentials");
        if (u.getStatus() == VerificationStatus.SUSPENDED)
            throw new UnauthorizedException("Account suspended");

        u.recordLogin(OffsetDateTime.now());
        audit.record(u.getId(), "USER_LOGIN", "User", u.getId().toString(), null);
        return mintAuthResponse(u, deviceId);
    }

    @Override
    @Transactional
    public AuthDtos.AuthResponse refresh(String refreshToken, String deviceId) {
        String hash = sha256(refreshToken);
        RefreshToken rt = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        OffsetDateTime now = OffsetDateTime.now();
        if (!rt.isUsable(now))
            throw new UnauthorizedException("Refresh token expired or revoked");

        // Rotate: revoke old, issue new.
        rt.revoke(now);
        return mintAuthResponse(rt.getUser(), deviceId);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        String hash = sha256(refreshToken);
        refreshTokens.findByTokenHash(hash).ifPresent(rt -> rt.revoke(OffsetDateTime.now()));
    }

    @Override
    public AuthDtos.StepUpResponse issueStepUp(UUID userId, String email, UserRole role) {
        var token = jwtService.issueAccess(userId, email, role, true);
        return new AuthDtos.StepUpResponse(token.token(), token.expiresAt());
    }

    @Override
    @Transactional
    public AuthDtos.StepUpResponse passwordStepUp(UUID userId, String password) {
        User u = users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        if (!passwordEncoder.matches(password, u.getPasswordHash()))
            throw new UnauthorizedException("Invalid credentials");
        if (u.getStatus() == VerificationStatus.SUSPENDED)
            throw new UnauthorizedException("Account suspended");
        audit.record(u.getId(), "STEP_UP_PASSWORD", "User", u.getId().toString(), null);
        return issueStepUp(u.getId(), u.getEmail(), u.getRole());
    }

    private AuthDtos.AuthResponse mintAuthResponse(User u, String deviceId) {
        var access = jwtService.issueAccess(u.getId(), u.getEmail(), u.getRole(), false);

        String refreshRaw = randomToken();
        OffsetDateTime expires = OffsetDateTime.now().plus(props.security().jwt().refreshTokenTtl());
        refreshTokens.save(new RefreshToken(u, sha256(refreshRaw), deviceId, expires));

        return new AuthDtos.AuthResponse(
                access.token(),
                access.expiresAt(),
                refreshRaw,
                expires,
                new AuthDtos.ProfileSummary(
                        u.getId(), u.getFullName(), u.getEmail(),
                        u.getIdNumber(), u.getProgram(), u.getRole(),
                        u.getStatus().name()));
    }

    private static String randomToken() {
        byte[] buf = new byte[48];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}

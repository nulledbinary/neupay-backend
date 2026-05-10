package ph.edu.neu.payment.domain.qr;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.neu.payment.api.dto.QrDtos;
import ph.edu.neu.payment.common.error.BadRequestException;
import ph.edu.neu.payment.common.error.NotFoundException;
import ph.edu.neu.payment.config.AppProperties;
import ph.edu.neu.payment.domain.user.User;
import ph.edu.neu.payment.domain.user.UserRepository;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

@Service
public class QrServiceImpl implements QrService {

    private static final SecureRandom RNG = new SecureRandom();

    private final QrTokenRepository qrTokens;
    private final UserRepository users;
    private final QrTokenSigner signer;
    private final QrCodeImageGenerator imageGenerator;
    private final AppProperties props;

    public QrServiceImpl(QrTokenRepository qrTokens,
                         UserRepository users,
                         QrTokenSigner signer,
                         QrCodeImageGenerator imageGenerator,
                         AppProperties props) {
        this.qrTokens = qrTokens;
        this.users = users;
        this.signer = signer;
        this.imageGenerator = imageGenerator;
        this.props = props;
    }

    @Override
    @Transactional
    public QrDtos.IssueResponse issue(UUID userId, QrMode mode) {
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expires = now.plus(props.qr().rotationWindow());
        String nonce = randomNonce();

        QrToken stored = new QrToken(user, nonce, mode, expires);
        qrTokens.save(stored);

        String token = signer.sign(new QrTokenSigner.QrPayload(
                mode, userId.toString(), nonce, expires.toEpochSecond()));

        int rotation = (int) props.qr().rotationWindow().toSeconds();
        return new QrDtos.IssueResponse(token, mode, now, expires, rotation);
    }

    @Override
    @Transactional
    public byte[] renderPng(UUID userId, QrMode mode) {
        QrDtos.IssueResponse issued = issue(userId, mode);
        return imageGenerator.toPng(issued.token(), props.qr().image().sizePx(), props.qr().image().margin());
    }

    @Override
    @Transactional
    public QrDtos.RedeemResponse redeem(String compactToken, UUID redeemedByUserId) {
        QrTokenSigner.QrPayload payload;
        try {
            payload = signer.verify(compactToken);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid QR token");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (payload.expiresAtEpochSeconds() < now.toEpochSecond()) {
            throw new BadRequestException("QR token expired");
        }

        QrToken stored = qrTokens.findByNonceForUpdate(payload.nonce())
                .orElseThrow(() -> new BadRequestException("Unknown QR token"));
        if (stored.isExpired(now)) throw new BadRequestException("QR token expired");
        if (stored.isConsumed())   throw new BadRequestException("QR token already used");
        if (!stored.getUser().getId().toString().equals(payload.userId()))
            throw new BadRequestException("QR token mismatch");

        User redeemedBy = users.findById(redeemedByUserId)
                .orElseThrow(() -> new NotFoundException("Redeemer not found"));
        stored.consume(redeemedBy, now);

        User u = stored.getUser();
        return new QrDtos.RedeemResponse(
                u.getId(),
                u.getFullName(),
                u.getIdNumber(),
                stored.getMode(),
                stored.getNonce());
    }

    @Override
    @Transactional(readOnly = true)
    public QrDtos.PreviewResponse preview(String compactToken) {
        QrTokenSigner.QrPayload payload;
        try {
            payload = signer.verify(compactToken);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid QR token");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (payload.expiresAtEpochSeconds() < now.toEpochSecond())
            throw new BadRequestException("QR token expired");

        QrToken stored = qrTokens.findByNonce(payload.nonce())
                .orElseThrow(() -> new BadRequestException("Unknown QR token"));
        if (stored.isExpired(now)) throw new BadRequestException("QR token expired");
        if (stored.isConsumed())   throw new BadRequestException("QR token already used");
        if (!stored.getUser().getId().toString().equals(payload.userId()))
            throw new BadRequestException("QR token mismatch");

        User u = stored.getUser();
        return new QrDtos.PreviewResponse(
                u.getId(), u.getFullName(), u.getIdNumber(),
                stored.getMode(), stored.getExpiresAt());
    }

    private static String randomNonce() {
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}

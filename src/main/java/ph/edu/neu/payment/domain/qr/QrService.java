package ph.edu.neu.payment.domain.qr;

import ph.edu.neu.payment.api.dto.QrDtos;

import java.util.UUID;

public interface QrService {

    /** Issue a fresh, signed, rotating QR token for the given user. */
    QrDtos.IssueResponse issue(UUID userId, QrMode mode);

    /** Render a previously-issued token (or freshly issue + render) as PNG bytes. */
    byte[] renderPng(UUID userId, QrMode mode);

    /** Cashier/terminal redeem: validates signature, expiry, single-use; returns scanned user. */
    QrDtos.RedeemResponse redeem(String compactToken, UUID redeemedByUserId);

    /** Peek at a QR token's recipient without consuming it. */
    QrDtos.PreviewResponse preview(String compactToken);
}

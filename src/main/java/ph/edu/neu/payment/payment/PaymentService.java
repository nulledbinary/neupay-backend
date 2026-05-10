package ph.edu.neu.payment.payment;

import ph.edu.neu.payment.api.dto.PaymentDtos;

import java.util.UUID;

public interface PaymentService {

    /**
     * A merchant/cashier scans the user's PAY_OUT QR and charges the wallet.
     * The {@code chargedByUserId} is the cashier's user id (audit trail).
     */
    PaymentDtos.TransactionResult charge(UUID chargedByUserId, PaymentDtos.InitiatePaymentRequest req);

    /**
     * A cashier accepts cash from a user and credits their wallet.
     * The user's CASH_IN QR token is consumed.
     */
    PaymentDtos.TransactionResult cashTopUp(UUID cashierUserId, PaymentDtos.TopUpRequest req);

    /**
     * Admin/cashier add-funds without a QR (e.g. fixing an incident, or in-person).
     * Requires step-up auth on the cashier's session.
     */
    PaymentDtos.TransactionResult adminCredit(UUID cashierUserId, PaymentDtos.AdminTopUpRequest req);

    /**
     * Peer-to-peer transfer triggered by the iOS scanner: debit the caller,
     * credit the QR holder. Single-use: the scanned token is consumed.
     */
    PaymentDtos.TransactionResult peerPay(UUID payerUserId, PaymentDtos.PeerPayRequest req);
}

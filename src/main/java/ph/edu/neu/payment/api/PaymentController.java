package ph.edu.neu.payment.api;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ph.edu.neu.payment.api.dto.PaymentDtos;
import ph.edu.neu.payment.auth.CurrentUser;
import ph.edu.neu.payment.common.idempotency.Idempotent;
import ph.edu.neu.payment.payment.PaymentService;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** A merchant scans a student's PAY_OUT QR and charges. */
    @PostMapping("/charge")
    @PreAuthorize("hasAnyRole('CASHIER','ADMIN')")
    @Idempotent
    public PaymentDtos.TransactionResult charge(@Valid @RequestBody PaymentDtos.InitiatePaymentRequest req) {
        return payments.charge(CurrentUser.require().id(), req);
    }

    /** A cashier scans a student's CASH_IN QR and credits cash. */
    @PostMapping("/cash-topup")
    @PreAuthorize("hasAnyRole('CASHIER','ADMIN')")
    @Idempotent
    public PaymentDtos.TransactionResult cashTopUp(@Valid @RequestBody PaymentDtos.TopUpRequest req) {
        return payments.cashTopUp(CurrentUser.require().id(), req);
    }

    /** Peer-to-peer pay: any signed-in user can scan another user's QR and send funds. */
    @PostMapping("/pay")
    @Idempotent
    public PaymentDtos.TransactionResult peerPay(@Valid @RequestBody PaymentDtos.PeerPayRequest req) {
        return payments.peerPay(CurrentUser.require().id(), req);
    }
}

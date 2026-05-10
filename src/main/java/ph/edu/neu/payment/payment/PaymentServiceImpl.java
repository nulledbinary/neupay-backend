package ph.edu.neu.payment.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.neu.payment.api.dto.PaymentDtos;
import ph.edu.neu.payment.api.dto.QrDtos;
import ph.edu.neu.payment.common.error.BadRequestException;
import ph.edu.neu.payment.common.error.ForbiddenException;
import ph.edu.neu.payment.common.error.InsufficientFundsException;
import ph.edu.neu.payment.common.error.NotFoundException;
import ph.edu.neu.payment.config.AppProperties;
import ph.edu.neu.payment.domain.audit.AuditService;
import ph.edu.neu.payment.domain.qr.QrMode;
import ph.edu.neu.payment.domain.qr.QrService;
import ph.edu.neu.payment.domain.transaction.Transaction;
import ph.edu.neu.payment.domain.transaction.TransactionCategory;
import ph.edu.neu.payment.domain.transaction.TransactionRepository;
import ph.edu.neu.payment.domain.user.User;
import ph.edu.neu.payment.domain.user.UserRepository;
import ph.edu.neu.payment.domain.wallet.Wallet;
import ph.edu.neu.payment.domain.wallet.WalletRepository;
import ph.edu.neu.payment.domain.wallet.WalletStatus;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final SecureRandom RNG = new SecureRandom();

    private final QrService qrService;
    private final WalletRepository wallets;
    private final TransactionRepository transactions;
    private final UserRepository users;
    private final AuditService audit;
    private final AppProperties props;

    public PaymentServiceImpl(QrService qrService,
                              WalletRepository wallets,
                              TransactionRepository transactions,
                              UserRepository users,
                              AuditService audit,
                              AppProperties props) {
        this.qrService = qrService;
        this.wallets = wallets;
        this.transactions = transactions;
        this.users = users;
        this.audit = audit;
        this.props = props;
    }

    @Override
    @Transactional
    public PaymentDtos.TransactionResult charge(UUID chargedByUserId, PaymentDtos.InitiatePaymentRequest req) {
        if (req.amount().compareTo(props.payment().maxSinglePayment()) > 0)
            throw new BadRequestException("Amount exceeds per-transaction limit");

        QrDtos.RedeemResponse redeemed = qrService.redeem(req.qrToken(), chargedByUserId);
        if (redeemed.mode() != QrMode.PAY_OUT)
            throw new BadRequestException("QR is not a PAY_OUT token");

        Wallet payer = lockWallet(redeemed.userId());
        BigDecimal newBalance;
        try {
            newBalance = payer.applyDelta(req.amount().negate());
        } catch (IllegalStateException ex) {
            String msg = ex.getMessage();
            if ("Insufficient funds".equals(msg)) throw new InsufficientFundsException(msg);
            throw new BadRequestException(msg);
        }

        User cashier = users.findById(chargedByUserId)
                .orElseThrow(() -> new NotFoundException("Cashier not found"));

        TransactionCategory cat = req.category() == null ? TransactionCategory.PAYMENT : req.category();
        Transaction tx = new Transaction(payer, req.amount().negate(), req.title(), cat,
                generateReference("PAY"), newBalance, cashier, null);
        transactions.save(tx);

        audit.record(chargedByUserId, "WALLET_CHARGE",
                "Wallet", payer.getId().toString(), tx.getReference());
        return result(tx);
    }

    @Override
    @Transactional
    public PaymentDtos.TransactionResult cashTopUp(UUID cashierUserId, PaymentDtos.TopUpRequest req) {
        guardCashier(cashierUserId);
        if (req.amount().compareTo(props.payment().maxSingleTopup()) > 0)
            throw new BadRequestException("Amount exceeds per-transaction top-up limit");

        QrDtos.RedeemResponse redeemed = qrService.redeem(req.qrToken(), cashierUserId);
        if (redeemed.mode() != QrMode.CASH_IN)
            throw new BadRequestException("QR is not a CASH_IN token");

        return creditWallet(redeemed.userId(), cashierUserId, req.amount(), req.note(), TransactionCategory.TOP_UP);
    }

    @Override
    @Transactional
    public PaymentDtos.TransactionResult adminCredit(UUID cashierUserId, PaymentDtos.AdminTopUpRequest req) {
        guardCashier(cashierUserId);
        if (req.amount().compareTo(props.payment().maxSingleTopup()) > 0)
            throw new BadRequestException("Amount exceeds per-transaction top-up limit");
        return creditWallet(req.userId(), cashierUserId, req.amount(), req.note(), TransactionCategory.TOP_UP);
    }

    @Override
    @Transactional
    public PaymentDtos.TransactionResult peerPay(UUID payerUserId, PaymentDtos.PeerPayRequest req) {
        if (req.amount().compareTo(props.payment().maxSinglePayment()) > 0)
            throw new BadRequestException("Amount exceeds per-transaction limit");

        QrDtos.RedeemResponse redeemed = qrService.redeem(req.qrToken(), payerUserId);
        UUID payeeUserId = redeemed.userId();
        if (payerUserId.equals(payeeUserId))
            throw new BadRequestException("Cannot pay yourself");

        // Lock both wallets in a stable order (lowest UUID first) to avoid deadlocks.
        UUID firstUser  = payerUserId.compareTo(payeeUserId) < 0 ? payerUserId : payeeUserId;
        UUID secondUser = payerUserId.equals(firstUser) ? payeeUserId : payerUserId;
        Wallet first  = lockWallet(firstUser);
        Wallet second = lockWallet(secondUser);
        Wallet payer  = firstUser.equals(payerUserId) ? first : second;
        Wallet payee  = firstUser.equals(payerUserId) ? second : first;

        if (payer.getStatus() != WalletStatus.ACTIVE)
            throw new BadRequestException("Your wallet is not active");
        if (payee.getStatus() != WalletStatus.ACTIVE)
            throw new BadRequestException("Recipient wallet not active");

        BigDecimal payerNewBalance;
        try {
            payerNewBalance = payer.applyDelta(req.amount().negate());
        } catch (IllegalStateException ex) {
            if ("Insufficient funds".equals(ex.getMessage()))
                throw new InsufficientFundsException(ex.getMessage());
            throw new BadRequestException(ex.getMessage());
        }
        BigDecimal payeeNewBalance = payee.applyDelta(req.amount());

        User payerUser = users.findById(payerUserId)
                .orElseThrow(() -> new NotFoundException("Payer not found"));

        String reference = generateReference("P2P");
        Transaction debit  = new Transaction(payer, req.amount().negate(), req.note(),
                TransactionCategory.TRANSFER, reference + "-D", payerNewBalance, payerUser, null);
        Transaction credit = new Transaction(payee, req.amount(), req.note(),
                TransactionCategory.TRANSFER, reference + "-C", payeeNewBalance, payerUser, null);
        transactions.save(debit);
        transactions.save(credit);

        audit.record(payerUserId, "WALLET_TRANSFER",
                "Wallet", payee.getId().toString(), reference);
        return result(debit);
    }

    private PaymentDtos.TransactionResult creditWallet(
            UUID payeeUserId, UUID cashierUserId, BigDecimal amount, String note, TransactionCategory category) {

        Wallet payee = lockWalletByUser(payeeUserId);
        if (payee.getStatus() != WalletStatus.ACTIVE)
            throw new BadRequestException("Recipient wallet not active");

        BigDecimal newBalance = payee.applyDelta(amount);
        User cashier = users.findById(cashierUserId)
                .orElseThrow(() -> new NotFoundException("Cashier not found"));

        Transaction tx = new Transaction(payee, amount, note, category,
                generateReference("TOP"), newBalance, cashier, null);
        transactions.save(tx);

        audit.record(cashierUserId, "WALLET_CREDIT",
                "Wallet", payee.getId().toString(), tx.getReference());
        return result(tx);
    }

    private void guardCashier(UUID userId) {
        var role = users.findById(userId).map(User::getRole)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (role != ph.edu.neu.payment.domain.user.UserRole.CASHIER
                && role != ph.edu.neu.payment.domain.user.UserRole.ADMIN) {
            throw new ForbiddenException("Only cashiers/admins may credit wallets");
        }
    }

    private Wallet lockWallet(UUID userId) {
        Wallet w = wallets.findByUser_Id(userId)
                .orElseThrow(() -> new NotFoundException("Wallet not found"));
        return wallets.findWithLockingById(w.getId())
                .orElseThrow(() -> new NotFoundException("Wallet not found"));
    }

    private Wallet lockWalletByUser(UUID userId) {
        return lockWallet(userId);
    }

    private static PaymentDtos.TransactionResult result(Transaction t) {
        return new PaymentDtos.TransactionResult(
                t.getId(), t.getReference(), t.getAmount(), t.getBalanceAfter(), t.getOccurredAt());
    }

    private static String generateReference(String prefix) {
        byte[] buf = new byte[12];
        RNG.nextBytes(buf);
        return prefix + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}

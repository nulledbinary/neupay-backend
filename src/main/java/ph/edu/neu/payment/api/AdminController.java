package ph.edu.neu.payment.api;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ph.edu.neu.payment.api.dto.AdminDtos;
import ph.edu.neu.payment.api.dto.PaymentDtos;
import ph.edu.neu.payment.api.dto.WalletDtos;
import ph.edu.neu.payment.auth.CurrentUser;
import ph.edu.neu.payment.auth.RequiresStepUp;
import ph.edu.neu.payment.common.idempotency.Idempotent;
import ph.edu.neu.payment.domain.transaction.TransactionCategory;
import ph.edu.neu.payment.domain.transaction.TransactionService;
import ph.edu.neu.payment.common.error.NotFoundException;
import ph.edu.neu.payment.domain.user.UserRepository;
import ph.edu.neu.payment.domain.user.UserRole;
import ph.edu.neu.payment.domain.user.UserService;
import ph.edu.neu.payment.domain.wallet.WalletProvisioner;
import ph.edu.neu.payment.domain.wallet.WalletService;
import ph.edu.neu.payment.payment.PaymentService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('CASHIER','ADMIN')")
public class AdminController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final WalletService wallets;
    private final WalletProvisioner walletProvisioner;
    private final PaymentService payments;
    private final TransactionService transactions;

    public AdminController(UserService userService,
                           UserRepository userRepository,
                           WalletService wallets,
                           WalletProvisioner walletProvisioner,
                           PaymentService payments,
                           TransactionService transactions) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.wallets = wallets;
        this.walletProvisioner = walletProvisioner;
        this.payments = payments;
        this.transactions = transactions;
    }

    @GetMapping("/users")
    public Page<AdminDtos.UserSummary> listUsers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return userService.search(q, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100))));
    }

    @GetMapping("/users/{id}")
    public AdminDtos.UserDetails userDetails(@PathVariable UUID id) {
        return userService.details(id);
    }

    /**
     * Resolve a user by their human-readable ID number. The dashboard prefers this
     * lookup so internal UUIDs never appear in browser URLs / shareable links.
     */
    @GetMapping("/users/by-id-number/{idNumber}")
    public AdminDtos.UserDetails userByIdNumber(@PathVariable String idNumber) {
        return userService.detailsByIdNumber(idNumber);
    }

    /**
     * Permanently delete a user. Their wallet, refresh tokens, biometric
     * credentials, QR tokens, idempotency keys cascade away. References from
     * historical transactions / audit logs / consumed QR tokens are nulled
     * out so the receipt log is preserved.
     */
    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userService.delete(id, CurrentUser.require().id());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{id}/wallet")
    public WalletDtos.WalletView walletForUser(@PathVariable UUID id) {
        // Self-heal: legacy users (bootstrap admin, pre-fix staff) may not have
        // a wallet row yet. Provision lazily so the UI never sees a 404 here.
        var user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found"));
        walletProvisioner.ensureFor(user);
        return wallets.getWalletForUser(id);
    }

    @PostMapping("/users/{id}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminDtos.UserDetails freeze(@PathVariable UUID id) {
        return userService.suspend(id, CurrentUser.require().id());
    }

    @PostMapping("/users/{id}/reinstate")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminDtos.UserDetails reinstate(@PathVariable UUID id) {
        return userService.reinstate(id, CurrentUser.require().id());
    }

    /** Create a new CASHIER or ADMIN account. */
    @PostMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminDtos.UserDetails createStaff(@Valid @RequestBody AdminDtos.CreateStaffRequest req) {
        return userService.createStaff(req, CurrentUser.require().id());
    }

    /**
     * Provision any role of user (student / faculty / cashier / admin) and
     * optionally seed an initial wallet balance — recorded as a TOP_UP
     * transaction so it surfaces in the receipt log.
     */
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminDtos.UserDetails createUser(@Valid @RequestBody AdminDtos.CreateUserRequest req) {
        return userService.createUser(req, CurrentUser.require().id());
    }

    /** Reassign a user's role. ADMIN-only. */
    @PatchMapping("/users/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminDtos.UserDetails changeRole(@PathVariable UUID id,
                                            @Valid @RequestBody AdminDtos.ChangeRoleRequest req) {
        UserRole role = req.role();
        return userService.changeRole(id, role, CurrentUser.require().id());
    }

    /**
     * Cashier credits a wallet directly (no QR). Requires step-up biometric auth on
     * the cashier's session — the cashier must have approved their Face ID prompt
     * within the last few minutes.
     */
    @PostMapping("/topup")
    @RequiresStepUp
    @Idempotent
    public PaymentDtos.TransactionResult topup(@Valid @RequestBody PaymentDtos.AdminTopUpRequest req) {
        return payments.adminCredit(CurrentUser.require().id(), req);
    }

    /** Cross-user transaction log. Optional category filter (e.g. TOP_UP for cash-ins only). */
    @GetMapping("/transactions")
    public AdminDtos.TransactionLogPage listTransactions(
            @RequestParam(required = false) TransactionCategory category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        return transactions.adminLog(category,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "occurredAt")));
    }

    /** Aggregated cash-in totals/counts grouped by day and recipient role. Drives the chart. */
    @GetMapping("/transactions/stats")
    public AdminDtos.CashInStats cashInStats(@RequestParam(defaultValue = "30") int days) {
        return transactions.cashInStats(days);
    }
}

package ph.edu.neu.payment.domain.user;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.security.crypto.password.PasswordEncoder;

import ph.edu.neu.payment.api.dto.AdminDtos;
import ph.edu.neu.payment.api.dto.PaymentDtos;
import ph.edu.neu.payment.common.error.ConflictException;
import ph.edu.neu.payment.common.error.NotFoundException;
import ph.edu.neu.payment.domain.audit.AuditService;
import ph.edu.neu.payment.domain.wallet.Wallet;
import ph.edu.neu.payment.domain.wallet.WalletProvisioner;
import ph.edu.neu.payment.domain.wallet.WalletRepository;
import ph.edu.neu.payment.domain.wallet.WalletService;
import ph.edu.neu.payment.payment.PaymentService;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository users;
    private final WalletRepository wallets;
    private final WalletService walletService;
    private final WalletProvisioner walletProvisioner;
    private final PaymentService paymentService;
    private final AuditService audit;
    private final PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager em;

    public UserServiceImpl(UserRepository users,
                           WalletRepository wallets,
                           WalletService walletService,
                           WalletProvisioner walletProvisioner,
                           PaymentService paymentService,
                           AuditService audit,
                           PasswordEncoder passwordEncoder) {
        this.users = users;
        this.wallets = wallets;
        this.walletService = walletService;
        this.walletProvisioner = walletProvisioner;
        this.paymentService = paymentService;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminDtos.UserSummary> search(String query, Pageable pageable) {
        Page<User> page = (query == null || query.isBlank())
                ? users.findAll(pageable)
                : users.search(query.trim(), pageable);
        return page.map(u -> new AdminDtos.UserSummary(
                u.getId(), u.getFullName(), u.getEmail(), u.getIdNumber(), u.getRole(), u.getStatus()));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDtos.UserDetails details(UUID userId) {
        User u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        Wallet w = wallets.findByUser_Id(userId).orElse(null);
        BigDecimal balance = w == null ? BigDecimal.ZERO : w.getBalance();
        String card = w == null ? null : w.getCardNumber();
        return new AdminDtos.UserDetails(u.getId(), u.getFullName(), u.getEmail(),
                u.getIdNumber(), u.getProgram(), u.getRole(), u.getStatus(),
                balance, card, u.getCreatedAt(), u.getLastLoginAt());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDtos.UserDetails detailsByIdNumber(String idNumber) {
        User u = users.findByIdNumber(idNumber)
                .orElseThrow(() -> new NotFoundException("User not found"));
        return details(u.getId());
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID actorUserId) {
        if (userId.equals(actorUserId)) {
            throw new ph.edu.neu.payment.common.error.ForbiddenException("Cannot delete your own account");
        }
        User u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));

        // Capture identifying details before deletion so the audit row is meaningful.
        String detail = u.getRole().name() + " · " + u.getIdNumber();

        // Null out non-cascading FK references that point at this user, so the
        // historical paper trail (transactions, audit log entries, consumed QR
        // tokens) survives the delete.
        em.createNativeQuery("UPDATE transactions SET initiated_by_user = NULL WHERE initiated_by_user = :id")
                .setParameter("id", userId)
                .executeUpdate();
        em.createNativeQuery("UPDATE qr_tokens SET consumed_by = NULL WHERE consumed_by = :id")
                .setParameter("id", userId)
                .executeUpdate();
        em.createNativeQuery("UPDATE audit_logs SET actor_user_id = NULL WHERE actor_user_id = :id")
                .setParameter("id", userId)
                .executeUpdate();

        users.deleteById(userId);

        audit.record(actorUserId, "USER_DELETE", "User", userId.toString(), detail);
    }

    @Override
    @Transactional
    public AdminDtos.UserDetails suspend(UUID userId, UUID actorUserId) {
        User u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        u.updateStatus(VerificationStatus.SUSPENDED);
        wallets.findByUser_Id(userId).ifPresent(w -> walletService.freeze(w.getId()));
        audit.record(actorUserId, "USER_SUSPEND", "User", userId.toString(), null);
        return details(userId);
    }

    @Override
    @Transactional
    public AdminDtos.UserDetails reinstate(UUID userId, UUID actorUserId) {
        User u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        u.updateStatus(VerificationStatus.VERIFIED);
        wallets.findByUser_Id(userId).ifPresent(w -> walletService.activate(w.getId()));
        audit.record(actorUserId, "USER_REINSTATE", "User", userId.toString(), null);
        return details(userId);
    }

    @Override
    @Transactional
    public AdminDtos.UserDetails createStaff(AdminDtos.CreateStaffRequest req, UUID actorUserId) {
        if (users.existsByEmailIgnoreCase(req.email()))
            throw new ConflictException("Email already registered");
        if (users.existsByIdNumber(req.idNumber()))
            throw new ConflictException("ID number already registered");

        UserRole role = switch (req.role()) {
            case CASHIER -> UserRole.CASHIER;
            case ADMIN   -> UserRole.ADMIN;
        };

        User staff = new User(
                req.fullName(),
                req.email().toLowerCase(),
                req.idNumber(),
                "Staff",
                role,
                VerificationStatus.VERIFIED,
                passwordEncoder.encode(req.temporaryPassword()));
        users.save(staff);
        walletProvisioner.ensureFor(staff);
        audit.record(actorUserId, "STAFF_CREATE", "User", staff.getId().toString(), role.name());
        return details(staff.getId());
    }

    @Override
    @Transactional
    public AdminDtos.UserDetails createUser(AdminDtos.CreateUserRequest req, UUID actorUserId) {
        if (users.existsByEmailIgnoreCase(req.email()))
            throw new ConflictException("Email already registered");
        if (users.existsByIdNumber(req.idNumber()))
            throw new ConflictException("ID number already registered");

        String program = (req.program() == null || req.program().isBlank())
                ? defaultProgramFor(req.role())
                : req.program().trim();

        User user = new User(
                req.fullName().trim(),
                req.email().trim().toLowerCase(),
                req.idNumber().trim(),
                program,
                req.role(),
                VerificationStatus.VERIFIED,
                passwordEncoder.encode(req.temporaryPassword()));
        users.save(user);
        walletProvisioner.ensureFor(user);
        audit.record(actorUserId, "USER_CREATE", "User", user.getId().toString(), req.role().name());

        BigDecimal seed = req.initialBalance();
        if (seed != null && seed.signum() > 0) {
            String note = (req.initialBalanceNote() == null || req.initialBalanceNote().isBlank())
                    ? "Opening balance for " + user.getFullName()
                    : req.initialBalanceNote().trim();
            paymentService.adminCredit(actorUserId,
                    new PaymentDtos.AdminTopUpRequest(user.getId(), seed, note));
            audit.record(actorUserId, "USER_INITIAL_BALANCE", "User", user.getId().toString(),
                    seed.toPlainString());
        }
        return details(user.getId());
    }

    @Override
    @Transactional
    public AdminDtos.UserDetails changeRole(UUID userId, UserRole newRole, UUID actorUserId) {
        User u = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (u.getRole() == newRole) {
            return details(userId);
        }
        UserRole previous = u.getRole();
        u.changeRole(newRole);
        audit.record(actorUserId, "USER_ROLE_CHANGE", "User", userId.toString(),
                previous.name() + "->" + newRole.name());
        return details(userId);
    }

    private static String defaultProgramFor(UserRole role) {
        return switch (role) {
            case STUDENT -> "Student";
            case FACULTY -> "Faculty";
            case CASHIER, ADMIN -> "Staff";
        };
    }
}

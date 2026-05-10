package ph.edu.neu.payment.domain.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import ph.edu.neu.payment.api.dto.AdminDtos;

import java.util.UUID;

public interface UserService {

    Page<AdminDtos.UserSummary> search(String query, Pageable pageable);

    AdminDtos.UserDetails details(UUID userId);

    AdminDtos.UserDetails detailsByIdNumber(String idNumber);

    /**
     * Permanently remove a user from the database — cascades wipe their wallet,
     * QR tokens, refresh tokens, biometrics, and idempotency keys. Non-cascading
     * FKs in transactions / qr_tokens.consumed_by / audit_logs.actor_user_id are
     * nulled out so historical records survive.
     */
    void delete(UUID userId, UUID actorUserId);

    AdminDtos.UserDetails suspend(UUID userId, UUID actorUserId);

    AdminDtos.UserDetails reinstate(UUID userId, UUID actorUserId);

    /** Provision a CASHIER or ADMIN account. Caller must already be an ADMIN. */
    AdminDtos.UserDetails createStaff(AdminDtos.CreateStaffRequest req, UUID actorUserId);

    /**
     * Provision an arbitrary role (student/faculty/cashier/admin) and optionally
     * seed the wallet with an initial balance, which is recorded as a TOP_UP
     * transaction tied to the actor.
     */
    AdminDtos.UserDetails createUser(AdminDtos.CreateUserRequest req, UUID actorUserId);

    /** Reassign a user's role. Caller must already be an ADMIN. */
    AdminDtos.UserDetails changeRole(UUID userId, UserRole newRole, UUID actorUserId);
}

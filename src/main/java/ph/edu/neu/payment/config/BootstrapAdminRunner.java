package ph.edu.neu.payment.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.neu.payment.domain.user.User;
import ph.edu.neu.payment.domain.user.UserRepository;
import ph.edu.neu.payment.domain.user.UserRole;
import ph.edu.neu.payment.domain.user.VerificationStatus;
import ph.edu.neu.payment.domain.wallet.WalletProvisioner;

@Configuration
public class BootstrapAdminRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    // Hard-coded superadmin. The application keeps an `email` column on the users
    // table for schema compatibility, but authentication is by ID number only —
    // this address is a placeholder that will never be exposed in any UI.
    static final String SUPERADMIN_ID_NUMBER = "22-14309-736";
    static final String SUPERADMIN_FULL_NAME = "Super Admin";
    static final String SUPERADMIN_PASSWORD  = "#Boris0617-2004";
    static final String SUPERADMIN_EMAIL     = "superadmin@neupay.local";

    @Bean
    public ApplicationRunner bootstrapAdmin(UserRepository users,
                                            PasswordEncoder encoder,
                                            WalletProvisioner walletProvisioner) {
        return args -> ensureSuperadmin(users, encoder, walletProvisioner);
    }

    @Transactional
    void ensureSuperadmin(UserRepository users, PasswordEncoder encoder,
                          WalletProvisioner walletProvisioner) {
        var existing = users.findByIdNumber(SUPERADMIN_ID_NUMBER);
        if (existing.isPresent()) {
            log.info("Superadmin {} already provisioned — skipping bootstrap", SUPERADMIN_ID_NUMBER);
            return;
        }

        User admin = new User(
                SUPERADMIN_FULL_NAME,
                SUPERADMIN_EMAIL,
                SUPERADMIN_ID_NUMBER,
                "Administration",
                UserRole.ADMIN,
                VerificationStatus.VERIFIED,
                encoder.encode(SUPERADMIN_PASSWORD));
        users.save(admin);
        walletProvisioner.ensureFor(admin);
        log.info("Provisioned hard-coded superadmin (ID {}, role ADMIN)", SUPERADMIN_ID_NUMBER);
    }
}

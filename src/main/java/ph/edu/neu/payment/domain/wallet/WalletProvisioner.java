package ph.edu.neu.payment.domain.wallet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.neu.payment.domain.user.User;

import java.time.Year;

/**
 * Idempotent wallet provisioning for any user. Returns the existing wallet
 * if one is already attached, or creates a fresh ACTIVE wallet whose card
 * number is derived from the user's ID number ({@code NEU-<idNumber>}).
 *
 * The card number is intentionally deterministic so the displayed wallet
 * identifier stays in lockstep with the institutional ID — no random
 * lookup needed during cashier troubleshooting.
 */
@Service
public class WalletProvisioner {

    private static final int VALID_FOR_YEARS = 5;

    private final WalletRepository wallets;

    public WalletProvisioner(WalletRepository wallets) {
        this.wallets = wallets;
    }

    @Transactional
    public Wallet ensureFor(User user) {
        return wallets.findByUser_Id(user.getId()).orElseGet(() -> create(user));
    }

    /** Public so other paths (e.g. registration) can format the card number identically. */
    public static String cardNumberFor(String idNumber) {
        if (idNumber == null || idNumber.isBlank()) {
            throw new IllegalArgumentException("ID number required for card number");
        }
        return "NEU-" + idNumber.trim();
    }

    private Wallet create(User user) {
        Wallet w = new Wallet(user, cardNumberFor(user.getIdNumber()),
                Year.now().getValue() + VALID_FOR_YEARS);
        return wallets.save(w);
    }
}

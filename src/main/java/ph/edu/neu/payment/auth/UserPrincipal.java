package ph.edu.neu.payment.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import ph.edu.neu.payment.domain.user.User;
import ph.edu.neu.payment.domain.user.UserRole;
import ph.edu.neu.payment.domain.user.VerificationStatus;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record UserPrincipal(
        UUID id,
        String idNumber,
        String passwordHash,
        UserRole role,
        VerificationStatus status) implements UserDetails {

    public static UserPrincipal from(User u) {
        return new UserPrincipal(u.getId(), u.getIdNumber(), u.getPasswordHash(), u.getRole(), u.getStatus());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return idNumber; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return status != VerificationStatus.SUSPENDED; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return status != VerificationStatus.SUSPENDED; }
}

package team.projectpulse.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import team.projectpulse.user.UserRepository;

/** Checks current account status after signature/expiry validation, on every bearer request. */
@Component
public class ActiveAccountJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository userRepository;
    private final JwtAuthenticationConverter delegate;

    public ActiveAccountJwtAuthenticationConverter(UserRepository userRepository, JwtAuthenticationConverter delegate) {
        this.userRepository = userRepository;
        this.delegate = delegate;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Object claim = jwt.getClaims().get("userId");
        // Accept the integer/string forms understood by UserUtils, without truncating or overflowing.
        Integer userId;
        try {
            if (!(claim instanceof Integer || claim instanceof Long || claim instanceof String)) {
                throw new NumberFormatException();
            }
            userId = Integer.valueOf(claim.toString());
            if (userId <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw new InvalidBearerTokenException("Invalid account identity.");
        }
        if (this.userRepository.findById(userId).filter(user -> user.isEnabled()).isEmpty()) {
            throw new InvalidBearerTokenException("Account is unavailable.");
        }
        return this.delegate.convert(jwt);
    }
}

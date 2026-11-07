package team.projectpulse.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import team.projectpulse.student.Student;
import team.projectpulse.user.UserRepository;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student T19; FR-SEC-active-account. */
@org.junit.jupiter.api.Tag("UC-STU-deactivate-student")
class ActiveAccountJwtAuthenticationConverterTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ActiveAccountJwtAuthenticationConverter converter;

    ActiveAccountJwtAuthenticationConverterTest() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("authorities");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(authorities);
        this.converter = new ActiveAccountJwtAuthenticationConverter(users, delegate);
    }

    private Jwt token(Object id) {
        var builder = Jwt.withTokenValue("signed-token-already-validated").header("alg", "RS256")
                .subject("student@abc.edu").claim("authorities", "ROLE_student");
        if (id != null) builder.claim("userId", id);
        return builder.build();
    }

    static Stream<Object> invalidIds() {
        return Stream.of(null, "bad", "", "1.0", 1.5, 0, -1, Long.MAX_VALUE, 2147483648L, true, java.util.List.of(1));
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void invalidIdentityFailsClosedWithoutDatabaseLookup(Object id) {
        assertThatThrownBy(() -> converter.convert(token(id))).isInstanceOf(InvalidBearerTokenException.class);
        verifyNoInteractions(users);
    }

    @Test
    void missingAccountFailsClosed() {
        when(users.findById(3)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> converter.convert(token(3))).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void currentStatusIsReadEveryTimeAndAuthoritiesArePreserved() {
        Student student = new Student();
        student.setEnabled(true);
        when(users.findById(3)).thenReturn(Optional.of(student));
        Jwt jwt = token(3L);
        var authentication = converter.convert(jwt);
        assertThat(authentication.getPrincipal()).isSameAs(jwt);
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("FACTOR_BEARER", "ROLE_student");
        student.setEnabled(false);
        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
        verify(users, times(2)).findById(3);
    }
}

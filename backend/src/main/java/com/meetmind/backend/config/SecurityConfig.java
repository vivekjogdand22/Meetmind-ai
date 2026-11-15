package com.meetmind.backend.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;


// ============================================================
// WHO MAY CALL WHAT
//
// Creating an account, logging in, the health checks and the API
// documentation are open. Every other endpoint needs the login token in the
// header  Authorization: Bearer <token>.
//
// The token is signed with the secret JWT_SECRET (at least 32 characters).
// Without it a random secret is made at startup, which works but logs
// everybody out whenever the backend restarts.
// ============================================================

@Configuration
@EnableWebSecurity
public class SecurityConfig
{
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String UNAUTHORIZED_BODY =
            "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Please log in.\"}";


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception
    {
        AuthenticationEntryPoint pleaseLogIn =
                (request, response, exception) ->
                {
                    response.setStatus(401);

                    response.setContentType("application/json");

                    response.getWriter().write(UNAUTHORIZED_BODY);
                };

        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(pleaseLogIn))
                .authorizeHttpRequests(
                        requests -> requests
                                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                                .requestMatchers(
                                        "/api/auth/register",
                                        "/api/auth/login",
                                        "/api/health",
                                        "/actuator/health",
                                        "/actuator/health/**",
                                        "/v3/api-docs/**",
                                        "/swagger-ui/**",
                                        "/swagger-ui.html"
                                ).permitAll()
                                .anyRequest().authenticated()
                )
                .oauth2ResourceServer(
                        resourceServer -> resourceServer
                                .jwt(Customizer.withDefaults())
                                .authenticationEntryPoint(pleaseLogIn)
                );

        return http.build();
    }


    @Bean
    public PasswordEncoder passwordEncoder()
    {
        return new BCryptPasswordEncoder();
    }


    @Bean
    public SecretKey jwtKey(@Value("${meetmind.jwt.secret:}") String secret)
    {
        byte[] bytes;

        if (secret == null || secret.isBlank())
        {
            bytes = new byte[32];

            new SecureRandom().nextBytes(bytes);

            LOG.warn(
                    "JWT_SECRET is not set. A random secret is used, so everybody is logged out "
                            + "whenever the backend restarts. Set JWT_SECRET to at least 32 characters."
            );
        }
        else
        {
            bytes = secret.getBytes(StandardCharsets.UTF_8);

            if (bytes.length < 32)
            {
                throw new IllegalStateException(
                        "JWT_SECRET is too short. Use at least 32 characters."
                );
            }
        }

        return new SecretKeySpec(bytes, "HmacSHA256");
    }


    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtKey)
    {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }


    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtKey)
    {
        return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}

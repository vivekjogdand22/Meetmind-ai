package com.meetmind.backend.service;

import com.meetmind.backend.dto.AuthDto;
import com.meetmind.backend.entity.User;
import com.meetmind.backend.exception.EmailAlreadyUsedException;
import com.meetmind.backend.exception.InvalidLoginException;
import com.meetmind.backend.repository.MeetingRepository;
import com.meetmind.backend.repository.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;


@Service
public class AuthService
{
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public static final int MIN_PASSWORD_LENGTH = 8;

    // BCrypt only looks at the first 72 bytes of a password.
    private static final int MAX_PASSWORD_BYTES = 72;

    private static final int MAX_NAME_LENGTH = 60;

    // checked when the email is unknown, so that "no such user" and "wrong
    // password" take about the same time
    private final String unknownUserHash;

    private final UserRepository userRepository;

    private final MeetingRepository meetingRepository;

    private final PasswordEncoder passwordEncoder;

    private final JwtEncoder jwtEncoder;

    private final Duration tokenLifetime;


    public AuthService(
            UserRepository userRepository,
            MeetingRepository meetingRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            @Value("${meetmind.jwt.hours:12}") long tokenHours)
    {
        this.userRepository = userRepository;

        this.meetingRepository = meetingRepository;

        this.passwordEncoder = passwordEncoder;

        this.jwtEncoder = jwtEncoder;

        this.tokenLifetime = Duration.ofHours(tokenHours);

        this.unknownUserHash = passwordEncoder.encode("no-such-user");
    }


    // The first account ever created also receives the meetings that existed
    // before accounts did, so nothing already uploaded is lost.
    @Transactional
    public AuthDto.AuthResponse register(AuthDto.RegisterRequest request)
    {
        String email = cleanEmail(request.email());

        checkPassword(request.password());

        if (userRepository.existsByEmail(email))
        {
            throw new EmailAlreadyUsedException(
                    "An account with this email already exists. Log in instead."
            );
        }


        boolean firstUser = userRepository.count() == 0;

        User user = new User();

        user.setEmail(email);

        user.setDisplayName(cleanName(request.name(), email));

        user.setPasswordHash(passwordEncoder.encode(request.password()));

        user = userRepository.save(user);

        if (firstUser)
        {
            meetingRepository.claimUnowned(user.getId());
        }

        return respond(user);
    }


    public AuthDto.AuthResponse login(AuthDto.LoginRequest request)
    {
        String email = request.email() == null ? "" : request.email().strip().toLowerCase();

        String password = request.password() == null ? "" : request.password();

        User user = userRepository.findByEmail(email).orElse(null);

        boolean correct =
                passwordEncoder.matches(
                        tooLongForBcrypt(password) ? "" : password,
                        user != null ? user.getPasswordHash() : unknownUserHash
                );

        if (user == null || !correct)
        {
            throw new InvalidLoginException("Incorrect email or password.");
        }

        return respond(user);
    }


    public AuthDto.UserInfo me(Long userId)
    {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () -> new InvalidLoginException("This account no longer exists. Please log in again.")
                        );

        return info(user);
    }


    // ============================================================
    // CHECKING WHAT THE USER TYPED
    // ============================================================

    private static String cleanEmail(String email)
    {
        String clean = email == null ? "" : email.strip().toLowerCase();

        if (clean.length() > 254 || !EMAIL.matcher(clean).matches())
        {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }

        return clean;
    }


    private static void checkPassword(String password)
    {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH)
        {
            throw new IllegalArgumentException(
                    "The password must have at least " + MIN_PASSWORD_LENGTH + " characters."
            );
        }

        if (tooLongForBcrypt(password))
        {
            throw new IllegalArgumentException("The password is too long. Please use fewer characters.");
        }
    }


    private static boolean tooLongForBcrypt(String password)
    {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }


    private static String cleanName(String name, String email)
    {
        String clean = name == null ? "" : name.strip();

        if (clean.isEmpty())
        {
            clean = email.substring(0, email.indexOf('@'));
        }

        return clean.length() > MAX_NAME_LENGTH ? clean.substring(0, MAX_NAME_LENGTH) : clean;
    }


    // ============================================================
    // THE LOGIN TOKEN
    // ============================================================

    private AuthDto.AuthResponse respond(User user)
    {
        Instant now = Instant.now();

        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer("meetmind")
                        .subject(String.valueOf(user.getId()))
                        .issuedAt(now)
                        .expiresAt(now.plus(tokenLifetime))
                        .build();

        String token =
                jwtEncoder
                        .encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(),
                                        claims
                                )
                        )
                        .getTokenValue();

        return new AuthDto.AuthResponse(token, tokenLifetime.toSeconds(), info(user));
    }


    private static AuthDto.UserInfo info(User user)
    {
        return new AuthDto.UserInfo(user.getId(), user.getEmail(), user.getDisplayName());
    }
}

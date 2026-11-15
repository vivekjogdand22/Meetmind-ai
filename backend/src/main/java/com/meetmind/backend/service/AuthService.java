package com.meetmind.backend.service;

import com.meetmind.backend.dto.AuthDto;
import com.meetmind.backend.entity.User;
import com.meetmind.backend.exception.EmailAlreadyUsedException;
import com.meetmind.backend.exception.InvalidLoginException;
import com.meetmind.backend.repository.MeetingRepository;
import com.meetmind.backend.repository.UserRepository;
import com.meetmind.backend.security.LoginThrottle;

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
import java.security.SecureRandom;
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

    // Recovery codes: 20 characters from an alphabet without look-alikes
    // (no 0/O, 1/I/L, U), about 98 bits. Shown as XXXXX-XXXXX-XXXXX-XXXXX.
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789";

    private static final int CODE_LENGTH = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    // checked when the email is unknown, so that "no such user" and "wrong
    // password" take about the same time
    private final String unknownUserHash;

    private final UserRepository userRepository;

    private final MeetingRepository meetingRepository;

    private final PasswordEncoder passwordEncoder;

    private final JwtEncoder jwtEncoder;

    private final LoginThrottle throttle;

    private final Duration tokenLifetime;


    public AuthService(
            UserRepository userRepository,
            MeetingRepository meetingRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            LoginThrottle throttle,
            @Value("${meetmind.jwt.hours:12}") long tokenHours)
    {
        this.userRepository = userRepository;

        this.meetingRepository = meetingRepository;

        this.passwordEncoder = passwordEncoder;

        this.jwtEncoder = jwtEncoder;

        this.throttle = throttle;

        this.tokenLifetime = Duration.ofHours(tokenHours);

        this.unknownUserHash = passwordEncoder.encode("no-such-user");
    }


    // ============================================================
    // CREATING AN ACCOUNT
    // ============================================================

    // The first account ever created also receives the meetings that existed
    // before accounts did, so nothing already uploaded is lost. The answer
    // contains the recovery code, which is shown only this once.
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

        String recoveryCode = newRecoveryCode();

        User user = new User();

        user.setEmail(email);

        user.setDisplayName(cleanName(request.name(), email));

        user.setPasswordHash(passwordEncoder.encode(request.password()));

        user.setRecoveryCodeHash(passwordEncoder.encode(normalizeCode(recoveryCode)));

        user = userRepository.save(user);

        if (firstUser)
        {
            meetingRepository.claimUnowned(user.getId());
        }

        return respond(user, recoveryCode);
    }


    // ============================================================
    // LOGGING IN
    // ============================================================

    public AuthDto.AuthResponse login(AuthDto.LoginRequest request, String address)
    {
        String email = request.email() == null ? "" : request.email().strip().toLowerCase();

        String password = request.password() == null ? "" : request.password();

        String emailKey = "login:" + email;

        String addressKey = "login-ip:" + address;

        // checked first: while locked, even the right password is refused
        throttle.check(emailKey, throttle.emailLimit());

        throttle.check(addressKey, throttle.addressLimit());

        User user = userRepository.findByEmail(email).orElse(null);

        boolean correct =
                passwordEncoder.matches(
                        tooLongForBcrypt(password) ? "" : password,
                        user != null ? user.getPasswordHash() : unknownUserHash
                );

        if (user == null || !correct)
        {
            throttle.fail(emailKey);

            throttle.fail(addressKey);

            throw new InvalidLoginException("Incorrect email or password.");
        }

        throttle.clear(emailKey);

        return respond(user, null);
    }


    public AuthDto.UserInfo me(Long userId)
    {
        return info(find(userId));
    }


    // ============================================================
    // FORGOT THE PASSWORD
    // ============================================================

    // With the recovery code from the account page or from the last reset the
    // password can be set again. That ends every login that is still open,
    // and a fresh recovery code replaces the one that was just used.
    @Transactional
    public AuthDto.AuthResponse resetPassword(AuthDto.ResetPasswordRequest request, String address)
    {
        String email = request.email() == null ? "" : request.email().strip().toLowerCase();

        String emailKey = "reset:" + email;

        String addressKey = "reset-ip:" + address;

        throttle.check(emailKey, throttle.emailLimit());

        throttle.check(addressKey, throttle.addressLimit());

        User user = userRepository.findByEmail(email).orElse(null);

        boolean hasCode = user != null && user.getRecoveryCodeHash() != null;

        boolean correct =
                passwordEncoder.matches(
                        normalizeCode(request.recoveryCode()),
                        hasCode ? user.getRecoveryCodeHash() : unknownUserHash
                );

        if (!hasCode || !correct)
        {
            throttle.fail(emailKey);

            throttle.fail(addressKey);

            throw new InvalidLoginException("The email or recovery code is not correct.");
        }

        checkPassword(request.newPassword());

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));

        user.setTokenVersion(user.getTokenVersion() + 1);

        String newCode = newRecoveryCode();

        user.setRecoveryCodeHash(passwordEncoder.encode(normalizeCode(newCode)));

        userRepository.save(user);

        throttle.clear(emailKey);

        throttle.clear("login:" + email);

        return respond(user, newCode);
    }


    // ============================================================
    // WHILE LOGGED IN
    // ============================================================

    // The answer carries a new token, so this browser stays logged in while
    // every other login is ended.
    @Transactional
    public AuthDto.AuthResponse changePassword(Long userId, AuthDto.ChangePasswordRequest request)
    {
        User user = confirmPassword(userId, request.currentPassword());

        checkPassword(request.newPassword());

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));

        user.setTokenVersion(user.getTokenVersion() + 1);

        userRepository.save(user);

        return respond(user, null);
    }


    // For accounts that have no recovery code yet, or lost it. The old code
    // stops working.
    @Transactional
    public AuthDto.RecoveryCodeResponse createRecoveryCode(Long userId, AuthDto.RecoveryCodeRequest request)
    {
        User user = confirmPassword(userId, request.password());

        String code = newRecoveryCode();

        user.setRecoveryCodeHash(passwordEncoder.encode(normalizeCode(code)));

        userRepository.save(user);

        return new AuthDto.RecoveryCodeResponse(code);
    }


    // Asks for the current password again. Wrong guesses are limited like
    // wrong logins, so a stolen open session cannot be used to guess it.
    private User confirmPassword(Long userId, String password)
    {
        User user = find(userId);

        String key = "confirm:" + userId;

        throttle.check(key, throttle.emailLimit());

        String given = password == null ? "" : password;

        if (tooLongForBcrypt(given) || !passwordEncoder.matches(given, user.getPasswordHash()))
        {
            throttle.fail(key);

            throw new IllegalArgumentException("Your current password is not correct.");
        }

        throttle.clear(key);

        return user;
    }


    private User find(Long userId)
    {
        return userRepository
                .findById(userId)
                .orElseThrow(
                        () -> new InvalidLoginException("This account no longer exists. Please log in again.")
                );
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
    // RECOVERY CODES
    // ============================================================

    private static String newRecoveryCode()
    {
        StringBuilder code = new StringBuilder();

        for (int position = 0; position < CODE_LENGTH; position++)
        {
            if (position > 0 && position % 5 == 0)
            {
                code.append('-');
            }

            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }

        return code.toString();
    }


    // Capitals, spaces and dashes do not matter when typing the code.
    private static String normalizeCode(String code)
    {
        return code == null ? "" : code.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }


    // ============================================================
    // THE LOGIN TOKEN
    // ============================================================

    private AuthDto.AuthResponse respond(User user, String recoveryCode)
    {
        Instant now = Instant.now();

        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer("meetmind")
                        .subject(String.valueOf(user.getId()))
                        .issuedAt(now)
                        .expiresAt(now.plus(tokenLifetime))
                        .claim("tv", user.getTokenVersion())
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

        return new AuthDto.AuthResponse(token, tokenLifetime.toSeconds(), info(user), recoveryCode);
    }


    private static AuthDto.UserInfo info(User user)
    {
        return new AuthDto.UserInfo(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRecoveryCodeHash() != null
        );
    }
}

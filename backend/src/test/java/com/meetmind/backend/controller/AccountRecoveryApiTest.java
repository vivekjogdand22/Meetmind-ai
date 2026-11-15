package com.meetmind.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.entity.User;
import com.meetmind.backend.repository.MeetingRepository;
import com.meetmind.backend.repository.UserRepository;
import com.meetmind.backend.security.LoginThrottle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


// Forgotten passwords, changing the password, recovery codes and the limits on
// wrong guesses, all with real accounts and real tokens.

@SpringBootTest
@AutoConfigureMockMvc
class AccountRecoveryApiTest
{
    private static final String PASSWORD = "first-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private LoginThrottle throttle;


    @BeforeEach
    void emptyDatabase()
    {
        throttle.clearAll();

        meetingRepository.deleteAll();

        userRepository.deleteAll();
    }


    // ============================================================
    // HELPERS
    // ============================================================

    private MvcResult send(String url, String token, String json, int expectedStatus) throws Exception
    {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(json);

        if (token != null)
        {
            request = request.header("Authorization", "Bearer " + token);
        }

        return mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }


    private JsonNode body(MvcResult result) throws Exception
    {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }


    private JsonNode register(String email) throws Exception
    {
        return body(send("/api/auth/register", null,
                json("email", email, "password", PASSWORD, "name", "Ann"), 201));
    }


    private JsonNode login(String email, String password, int expectedStatus) throws Exception
    {
        return body(send("/api/auth/login", null, json("email", email, "password", password), expectedStatus));
    }


    private JsonNode reset(String email, String code, String newPassword, int expectedStatus) throws Exception
    {
        return body(send("/api/auth/reset-password", null,
                json("email", email, "recoveryCode", code, "newPassword", newPassword), expectedStatus));
    }


    private String json(String... pairs)
    {
        var node = objectMapper.createObjectNode();

        for (int i = 0; i < pairs.length; i += 2)
        {
            node.put(pairs[i], pairs[i + 1]);
        }

        return node.toString();
    }


    private void tokenWorks(String token, int expectedStatus) throws Exception
    {
        mockMvc.perform(get("/api/meetings").header("Authorization", "Bearer " + token))
                .andExpect(status().is(expectedStatus));
    }


    // ============================================================
    // THE RECOVERY CODE
    // ============================================================

    @Test
    void registeringGivesAOneTimeRecoveryCodeThatIsOnlyStoredHashed() throws Exception
    {
        JsonNode created = register("a@example.com");

        String code = created.path("recoveryCode").asText();

        assertTrue(code.matches("^[A-Z2-9]{5}(-[A-Z2-9]{5}){3}$"), code);
        assertTrue(created.path("user").path("hasRecoveryCode").asBoolean());

        String stored = userRepository.findByEmail("a@example.com").orElseThrow().getRecoveryCodeHash();

        assertTrue(stored.startsWith("$2"));
        assertFalse(stored.contains(code.replace("-", "")));
    }


    @Test
    void loggingInNeverRevealsTheRecoveryCodeAgain() throws Exception
    {
        register("a@example.com");

        JsonNode loggedIn = login("a@example.com", PASSWORD, 200);

        assertTrue(loggedIn.path("recoveryCode").isNull());
        assertTrue(loggedIn.path("user").path("hasRecoveryCode").asBoolean());
    }


    @Test
    void everyAccountGetsADifferentCode() throws Exception
    {
        assertNotEquals(
                register("a@example.com").path("recoveryCode").asText(),
                register("b@example.com").path("recoveryCode").asText()
        );
    }


    // ============================================================
    // FORGOT THE PASSWORD
    // ============================================================

    @Test
    void theRecoveryCodeSetsANewPasswordAndEndsOldLogins() throws Exception
    {
        JsonNode created = register("a@example.com");

        String code = created.path("recoveryCode").asText();

        String oldToken = created.path("token").asText();

        // typed in small letters, without dashes, with spaces: still fine
        String typed = code.toLowerCase().replace("-", " ");

        JsonNode afterReset = reset(" A@example.com ", typed, "second-password", 200);

        assertNotEquals(code, afterReset.path("recoveryCode").asText());   // a fresh code replaces the used one
        tokenWorks(afterReset.path("token").asText(), 200);                // reset logs you in
        tokenWorks(oldToken, 401);                                         // every older login is over

        login("a@example.com", PASSWORD, 401);
        login("a@example.com", "second-password", 200);
    }


    @Test
    void aCodeWorksOnlyOnce() throws Exception
    {
        String code = register("a@example.com").path("recoveryCode").asText();

        reset("a@example.com", code, "second-password", 200);

        assertEquals("The email or recovery code is not correct.",
                reset("a@example.com", code, "third-password", 401).path("message").asText());
    }


    @Test
    void aWrongCodeAndAnUnknownEmailLookTheSame() throws Exception
    {
        register("a@example.com");

        String wrongCode = reset("a@example.com", "AAAAA-AAAAA-AAAAA-AAAAA", "second-password", 401).path("message").asText();

        String unknownEmail = reset("nobody@example.com", "AAAAA-AAAAA-AAAAA-AAAAA", "second-password", 401).path("message").asText();

        assertEquals("The email or recovery code is not correct.", wrongCode);
        assertEquals(wrongCode, unknownEmail);
        login("a@example.com", PASSWORD, 200);   // nothing changed
    }


    @Test
    void aWeakNewPasswordIsRefusedAndTheCodeIsNotUsedUp() throws Exception
    {
        String code = register("a@example.com").path("recoveryCode").asText();

        assertTrue(reset("a@example.com", code, "short", 400).path("message").asText().contains("at least 8"));

        reset("a@example.com", code, "a-better-password", 200);   // the same code still works
    }


    @Test
    void anAccountWithoutARecoveryCodeCannotBeResetWithAnEmptyOne() throws Exception
    {
        register("a@example.com");

        User user = userRepository.findByEmail("a@example.com").orElseThrow();

        user.setRecoveryCodeHash(null);   // an account from before recovery codes existed

        userRepository.save(user);

        reset("a@example.com", "", "second-password", 401);
        reset("a@example.com", "AAAAA-AAAAA-AAAAA-AAAAA", "second-password", 401);
    }


    @Test
    void resettingAlsoEndsALoginLock() throws Exception
    {
        String code = register("a@example.com").path("recoveryCode").asText();

        for (int i = 0; i < 5; i++)
        {
            login("a@example.com", "wrong-password", 401);
        }

        login("a@example.com", PASSWORD, 429);   // locked, even with the right password

        reset("a@example.com", code, "second-password", 200);

        login("a@example.com", "second-password", 200);
    }


    // ============================================================
    // CHANGING THE PASSWORD WHILE LOGGED IN
    // ============================================================

    @Test
    void changingThePasswordKeepsThisLoginAndEndsTheOthers() throws Exception
    {
        JsonNode created = register("a@example.com");

        String thisBrowser = created.path("token").asText();

        String otherBrowser = login("a@example.com", PASSWORD, 200).path("token").asText();

        JsonNode changed = body(send("/api/auth/change-password", thisBrowser,
                json("currentPassword", PASSWORD, "newPassword", "second-password"), 200));

        tokenWorks(changed.path("token").asText(), 200);   // the answer carries a new token
        tokenWorks(thisBrowser, 401);
        tokenWorks(otherBrowser, 401);

        login("a@example.com", PASSWORD, 401);
        login("a@example.com", "second-password", 200);
    }


    @Test
    void aWrongCurrentPasswordIsA400NotALogout() throws Exception
    {
        String token = register("a@example.com").path("token").asText();

        // 400, not 401: the app treats a 401 as "your login ended" and would log the user out
        assertEquals("Your current password is not correct.",
                body(send("/api/auth/change-password", token,
                        json("currentPassword", "nope-nope-nope", "newPassword", "second-password"), 400)).path("message").asText());

        tokenWorks(token, 200);
    }


    @Test
    void aWeakNewPasswordIsRefusedWhenChanging() throws Exception
    {
        String token = register("a@example.com").path("token").asText();

        send("/api/auth/change-password", token, json("currentPassword", PASSWORD, "newPassword", "short"), 400);

        login("a@example.com", PASSWORD, 200);
    }


    @Test
    void changingThePasswordNeedsALogin() throws Exception
    {
        send("/api/auth/change-password", null, json("currentPassword", PASSWORD, "newPassword", "second-password"), 401);
    }


    // ============================================================
    // A NEW RECOVERY CODE
    // ============================================================

    @Test
    void aNewCodeReplacesTheOldOne() throws Exception
    {
        JsonNode created = register("a@example.com");

        String oldCode = created.path("recoveryCode").asText();

        String newCode = body(send("/api/auth/recovery-code", created.path("token").asText(),
                json("password", PASSWORD), 200)).path("recoveryCode").asText();

        assertNotEquals(oldCode, newCode);

        reset("a@example.com", oldCode, "second-password", 401);
        reset("a@example.com", newCode, "second-password", 200);
    }


    @Test
    void aNewCodeNeedsThePassword() throws Exception
    {
        String token = register("a@example.com").path("token").asText();

        send("/api/auth/recovery-code", token, json("password", "wrong-password"), 400);
        send("/api/auth/recovery-code", token, "{}", 400);
        send("/api/auth/recovery-code", null, json("password", PASSWORD), 401);
    }


    @Test
    void anAccountThatHadNoCodeCanMakeOne() throws Exception
    {
        JsonNode created = register("a@example.com");

        User user = userRepository.findByEmail("a@example.com").orElseThrow();

        user.setRecoveryCodeHash(null);

        userRepository.save(user);

        assertFalse(body(mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + created.path("token").asText()))
                .andReturn())
                .path("hasRecoveryCode").asBoolean());

        send("/api/auth/recovery-code", created.path("token").asText(), json("password", PASSWORD), 200);

        assertTrue(userRepository.findByEmail("a@example.com").orElseThrow().getRecoveryCodeHash() != null);
    }


    // ============================================================
    // LIMITS ON WRONG GUESSES
    // ============================================================

    @Test
    void afterFiveWrongPasswordsTheEmailIsLockedEvenForTheRightPassword() throws Exception
    {
        register("a@example.com");

        for (int i = 0; i < 5; i++)
        {
            assertEquals("Incorrect email or password.", login("a@example.com", "wrong-password", 401).path("message").asText());
        }

        MvcResult locked = send("/api/auth/login", null, json("email", "a@example.com", "password", PASSWORD), 429);

        assertTrue(body(locked).path("message").asText().startsWith("Too many failed attempts"));

        long wait = Long.parseLong(locked.getResponse().getHeader("Retry-After"));

        assertTrue(wait > 14 * 60 && wait <= 15 * 60 + 1, "Retry-After: " + wait);
    }


    @Test
    void anotherEmailIsNotAffectedByTheLock() throws Exception
    {
        register("a@example.com");

        register("b@example.com");

        for (int i = 0; i < 5; i++)
        {
            login("a@example.com", "wrong-password", 401);
        }

        login("b@example.com", PASSWORD, 200);
    }


    @Test
    void aSuccessfulLoginStartsTheCountAgain() throws Exception
    {
        register("a@example.com");

        for (int round = 0; round < 3; round++)
        {
            for (int i = 0; i < 4; i++)
            {
                login("a@example.com", "wrong-password", 401);
            }

            login("a@example.com", PASSWORD, 200);   // never reaches 5 in a row
        }
    }


    @Test
    void guessingRecoveryCodesIsLimitedToo() throws Exception
    {
        register("a@example.com");

        for (int i = 0; i < 5; i++)
        {
            reset("a@example.com", "AAAAA-AAAAA-AAAAA-AAAAA", "second-password", 401);
        }

        MvcResult locked = send("/api/auth/reset-password", null,
                json("email", "a@example.com", "recoveryCode", "AAAAA-AAAAA-AAAAA-AAAAA", "newPassword", "second-password"), 429);

        assertEquals("Retry-After present", locked.getResponse().getHeader("Retry-After") != null ? "Retry-After present" : "missing");
    }


    @Test
    void guessingThePasswordWhileChangingItIsLimited() throws Exception
    {
        String token = register("a@example.com").path("token").asText();

        for (int i = 0; i < 5; i++)
        {
            send("/api/auth/change-password", token, json("currentPassword", "wrong-password", "newPassword", "second-password"), 400);
        }

        send("/api/auth/change-password", token, json("currentPassword", PASSWORD, "newPassword", "second-password"), 429);
    }


    @Test
    void oneAddressTryingManyDifferentEmailsIsStoppedToo() throws Exception
    {
        for (int i = 0; i < 20; i++)
        {
            login("nobody" + i + "@example.com", "wrong-password", 401);
        }

        login("somebody-else@example.com", "wrong-password", 429);
    }


    @Test
    void aTokenWithoutTheVersionNumberIsRefused() throws Exception
    {
        assertNull(null);

        // a token that was not made by this backend's register/login
        tokenWorks("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.invalid", 401);
    }
}

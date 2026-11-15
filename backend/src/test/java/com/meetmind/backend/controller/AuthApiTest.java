package com.meetmind.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetmind.backend.entity.Meeting;
import com.meetmind.backend.repository.MeetingRepository;
import com.meetmind.backend.repository.UserRepository;
import com.meetmind.backend.security.LoginThrottle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


// Creating accounts and logging in, with real passwords and real login tokens.

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest
{
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private LoginThrottle throttle;


    @BeforeEach
    void emptyDatabase()
    {
        throttle.clearAll();

        meetingRepository.deleteAll();

        userRepository.deleteAll();
    }


    private JsonNode send(String url, String json, int expectedStatus) throws Exception
    {
        String body =
                mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return objectMapper.readTree(body);
    }


    private JsonNode register(String email, String password, String name, int expectedStatus) throws Exception
    {
        return send(
                "/api/auth/register",
                objectMapper.createObjectNode()
                        .put("email", email)
                        .put("password", password)
                        .put("name", name)
                        .toString(),
                expectedStatus
        );
    }


    private JsonNode login(String email, String password, int expectedStatus) throws Exception
    {
        return send(
                "/api/auth/login",
                objectMapper.createObjectNode().put("email", email).put("password", password).toString(),
                expectedStatus
        );
    }


    // ============================================================
    // CREATING AN ACCOUNT
    // ============================================================

    @Test
    void anAccountCanBeCreatedAndTheTokenWorks() throws Exception
    {
        JsonNode created = register("  Vivek@Example.COM ", "a-good-password", "Vivek", 201);

        assertFalse(created.path("token").asText().isBlank());
        assertEquals("vivek@example.com", created.path("user").path("email").asText());
        assertEquals("Vivek", created.path("user").path("name").asText());
        assertEquals(12 * 3600, created.path("expiresInSeconds").asLong());

        mockMvc.perform(get("/api/meetings").header("Authorization", "Bearer " + created.path("token").asText()))
                .andExpect(status().isOk());
    }


    @Test
    void thePasswordIsNeverStoredOrReturned() throws Exception
    {
        JsonNode created = register("a@example.com", "a-good-password", "A", 201);

        assertFalse(created.toString().contains("a-good-password"));
        assertFalse(created.toString().toLowerCase().contains("hash"));

        String stored = userRepository.findByEmail("a@example.com").orElseThrow().getPasswordHash();

        assertNotEquals("a-good-password", stored);
        assertTrue(stored.startsWith("$2"));   // a BCrypt hash
    }


    @Test
    void theNameDefaultsToTheStartOfTheEmail() throws Exception
    {
        assertEquals("meera", register("meera@example.com", "a-good-password", "  ", 201).path("user").path("name").asText());
    }


    @Test
    void badEmailsAndPasswordsAreRefusedWithAClearMessage() throws Exception
    {
        assertEquals("Please enter a valid email address.", register("not-an-email", "a-good-password", "A", 400).path("message").asText());
        assertEquals("Please enter a valid email address.", register("", "a-good-password", "A", 400).path("message").asText());
        assertTrue(register("a@example.com", "short", "A", 400).path("message").asText().contains("at least 8"));
        assertTrue(register("a@example.com", "x".repeat(73), "A", 400).path("message").asText().contains("too long"));
        assertTrue(register("a@example.com", "é".repeat(40), "A", 400).path("message").asText().contains("too long"));   // 80 bytes

        assertEquals(0, userRepository.count());
    }


    @Test
    void theSameEmailCannotBeUsedTwiceEvenWithOtherCapitals() throws Exception
    {
        register("a@example.com", "a-good-password", "A", 201);

        JsonNode second = register("A@Example.com", "another-password", "B", 409);

        assertTrue(second.path("message").asText().contains("already exists"));
        assertEquals(1, userRepository.count());
    }


    @Test
    void theFirstAccountClaimsTheMeetingsThatExistedBeforeAccounts() throws Exception
    {
        Meeting old = new Meeting();

        old.setTitle("Uploaded before accounts existed");

        old = meetingRepository.save(old);

        JsonNode first = register("first@example.com", "a-good-password", "First", 201);

        assertEquals(first.path("user").path("id").asLong(), meetingRepository.findById(old.getId()).orElseThrow().getOwnerId());

        // a later account gets nothing
        Meeting later = new Meeting();

        later.setTitle("Unowned again");

        later = meetingRepository.save(later);

        register("second@example.com", "a-good-password", "Second", 201);

        assertNull(meetingRepository.findById(later.getId()).orElseThrow().getOwnerId());
    }


    // ============================================================
    // LOGGING IN
    // ============================================================

    @Test
    void loggingInWorksWithTheRightPassword() throws Exception
    {
        register("a@example.com", "a-good-password", "A", 201);

        JsonNode loggedIn = login(" A@example.com", "a-good-password", 200);

        assertFalse(loggedIn.path("token").asText().isBlank());
        assertEquals("a@example.com", loggedIn.path("user").path("email").asText());
    }


    @Test
    void aWrongPasswordAndAnUnknownEmailLookTheSame() throws Exception
    {
        register("a@example.com", "a-good-password", "A", 201);

        String wrongPassword = login("a@example.com", "not-the-password", 401).path("message").asText();

        String unknownEmail = login("nobody@example.com", "a-good-password", 401).path("message").asText();

        assertEquals("Incorrect email or password.", wrongPassword);
        assertEquals(wrongPassword, unknownEmail);
    }


    @Test
    void emptyLoginFieldsAreRefused() throws Exception
    {
        send("/api/auth/login", "{}", 401);
    }


    // ============================================================
    // THE TOKEN
    // ============================================================

    @Test
    void meReturnsTheLoggedInUser() throws Exception
    {
        JsonNode created = register("a@example.com", "a-good-password", "Ann", 201);

        String body =
                mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + created.path("token").asText()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        JsonNode me = objectMapper.readTree(body);

        assertEquals("Ann", me.path("name").asText());
        assertEquals(created.path("user").path("id").asLong(), me.path("id").asLong());
    }


    @Test
    void withoutATokenEverythingButLoginIsClosedWithAJsonMessage() throws Exception
    {
        for (String url : new String[] {"/api/meetings", "/api/auth/me", "/api/meetings/1/plan"})
        {
            String body =
                    mockMvc.perform(get(url)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

            assertEquals("Please log in.", objectMapper.readTree(body).path("message").asText());
        }

        mockMvc.perform(post("/api/meetings/1/ask").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }


    @Test
    void healthChecksAndTheApiDocsStayOpen() throws Exception
    {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }


    @Test
    void aTamperedTokenIsRefused() throws Exception
    {
        String token = register("a@example.com", "a-good-password", "A", 201).path("token").asText();

        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");

        mockMvc.perform(get("/api/meetings").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/meetings").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }


    @Test
    void anExpiredTokenIsRefused() throws Exception
    {
        long id = register("a@example.com", "a-good-password", "A", 201).path("user").path("id").asLong();

        Instant now = Instant.now();

        String expired =
                jwtEncoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(),
                                        JwtClaimsSet.builder()
                                                .subject(String.valueOf(id))
                                                .issuedAt(now.minus(3, ChronoUnit.HOURS))
                                                .expiresAt(now.minus(2, ChronoUnit.HOURS))
                                                .build()
                                )
                        )
                        .getTokenValue();

        mockMvc.perform(get("/api/meetings").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }


    @Test
    void aTokenOfADeletedAccountIsRefusedByMe() throws Exception
    {
        JsonNode created = register("a@example.com", "a-good-password", "A", 201);

        userRepository.deleteAll();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + created.path("token").asText()))
                .andExpect(status().isUnauthorized());

        assertNotNull(created);
    }
}

package com.meetmind.backend.security;

import com.meetmind.backend.exception.TooManyAttemptsException;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


// The failure counter, with a clock that the test moves by hand.

class LoginThrottleTest
{
    // a clock that only moves when the test says so
    private static final class MovableClock extends Clock
    {
        private Instant now = Instant.parse("2026-01-01T10:00:00Z");

        void advance(Duration time)
        {
            now = now.plus(time);
        }

        @Override
        public java.time.ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }


    private final MovableClock clock = new MovableClock();

    private final LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), clock);


    private void failTimes(String key, int times)
    {
        for (int i = 0; i < times; i++)
        {
            throttle.check(key, 3);

            throttle.fail(key);
        }
    }


    @Test
    void tryingIsAllowedUntilTheLimitIsReached()
    {
        failTimes("login:a", 3);   // three wrong tries are allowed to happen

        assertThrows(TooManyAttemptsException.class, () -> throttle.check("login:a", 3));
    }


    @Test
    void twoFailuresAreNotYetALock()
    {
        failTimes("login:a", 2);

        assertDoesNotThrow(() -> throttle.check("login:a", 3));
    }


    @Test
    void theLockTellsHowLongToWait()
    {
        failTimes("login:a", 3);

        clock.advance(Duration.ofMinutes(5));

        TooManyAttemptsException locked =
                assertThrows(TooManyAttemptsException.class, () -> throttle.check("login:a", 3));

        // the first failure was 5 minutes ago, so about 10 minutes are left
        assertTrue(locked.getRetryAfterSeconds() >= 9 * 60 && locked.getRetryAfterSeconds() <= 10 * 60 + 1,
                "seconds to wait: " + locked.getRetryAfterSeconds());
        assertTrue(locked.getMessage().contains("10 minutes"), locked.getMessage());
    }


    @Test
    void theLockEndsWhenTheOldestFailureIsOldEnough()
    {
        failTimes("login:a", 3);

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertDoesNotThrow(() -> throttle.check("login:a", 3));
    }


    @Test
    void failuresSpreadOverTimeDoNotAddUpForever()
    {
        failTimes("login:a", 2);

        clock.advance(Duration.ofMinutes(16));   // both are forgotten

        failTimes("login:a", 2);

        assertDoesNotThrow(() -> throttle.check("login:a", 3));
    }


    @Test
    void everyKeyIsCountedOnItsOwn()
    {
        failTimes("login:a", 3);

        assertDoesNotThrow(() -> throttle.check("login:b", 3));
    }


    @Test
    void aSuccessfulLoginClearsTheCount()
    {
        failTimes("login:a", 3);

        throttle.clear("login:a");

        assertDoesNotThrow(() -> throttle.check("login:a", 3));
    }


    @Test
    void anAddressGetsMoreTriesThanOneEmail()
    {
        assertEquals(3, throttle.emailLimit());
        assertEquals(12, throttle.addressLimit());
    }


    @Test
    void memoryStaysBoundedWhenManyDifferentKeysFail()
    {
        for (int i = 0; i < 12_000; i++)
        {
            throttle.fail("login:user" + i + "@example.com");
        }

        // the oldest keys were dropped, the newest are still counted
        assertDoesNotThrow(() -> throttle.check("login:user0@example.com", 1));
        assertThrows(TooManyAttemptsException.class, () -> throttle.check("login:user11999@example.com", 1));
    }
}

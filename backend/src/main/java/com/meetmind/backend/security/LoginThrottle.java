package com.meetmind.backend.security;

import com.meetmind.backend.exception.TooManyAttemptsException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;


// ============================================================
// LIMITS GUESSING OF PASSWORDS AND RECOVERY CODES
//
// Every failed try is remembered for a while, per key (for example one email,
// or one address). Once a key has failed too often, further tries are refused
// until the oldest failure is old enough, even with the right password. A
// successful login clears the count of that email.
//
// The counts live in memory: they reset when the backend restarts, and they
// are per backend (fine for one server). An attacker can also lock a known
// email out for a while; that is the price of stopping guessing.
// ============================================================

@Component
public class LoginThrottle
{
    // so that failures of one-off keys cannot fill the memory
    private static final int MAX_KEYS = 10_000;

    private final int maxFailures;

    private final Duration window;

    private final Clock clock;

    private final Map<String, Deque<Instant>> failures = new LinkedHashMap<>();


    @Autowired
    public LoginThrottle(
            @Value("${meetmind.security.max-failures:5}") int maxFailures,
            @Value("${meetmind.security.window-minutes:15}") long windowMinutes)
    {
        this(maxFailures, Duration.ofMinutes(windowMinutes), Clock.systemUTC());
    }


    public LoginThrottle(int maxFailures, Duration window, Clock clock)
    {
        this.maxFailures = maxFailures;

        this.window = window;

        this.clock = clock;
    }


    // One address may serve several people (an office, Docker's network), so
    // it gets more tries than one email.
    public int addressLimit()
    {
        return maxFailures * 4;
    }


    public int emailLimit()
    {
        return maxFailures;
    }


    // Throws when the key has already failed "limit" times inside the window.
    public synchronized void check(String key, int limit)
    {
        Deque<Instant> recent = recent(key);

        if (recent != null && recent.size() >= limit)
        {
            Instant freeAgain = recent.peekFirst().plus(window);

            throw new TooManyAttemptsException(
                    Math.max(1, Duration.between(clock.instant(), freeAgain).toSeconds() + 1)
            );
        }
    }


    public synchronized void fail(String key)
    {
        Deque<Instant> recent = recent(key);

        if (recent == null)
        {
            recent = new ArrayDeque<>();

            failures.put(key, recent);

            while (failures.size() > MAX_KEYS)
            {
                failures.remove(failures.keySet().iterator().next());
            }
        }

        recent.addLast(clock.instant());
    }


    // for the tests: forget everything
    public synchronized void clearAll()
    {
        failures.clear();
    }


    public synchronized void clear(String key)
    {
        failures.remove(key);
    }


    // The failures of this key that are still inside the window.
    private Deque<Instant> recent(String key)
    {
        Deque<Instant> recent = failures.get(key);

        if (recent == null)
        {
            return null;
        }

        Instant oldestAllowed = clock.instant().minus(window);

        for (Iterator<Instant> it = recent.iterator(); it.hasNext(); )
        {
            if (it.next().isAfter(oldestAllowed))
            {
                break;
            }

            it.remove();
        }

        if (recent.isEmpty())
        {
            failures.remove(key);

            return null;
        }

        return recent;
    }
}

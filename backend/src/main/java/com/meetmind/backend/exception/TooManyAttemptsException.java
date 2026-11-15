package com.meetmind.backend.exception;

public class TooManyAttemptsException extends RuntimeException
{
    private final long retryAfterSeconds;


    public TooManyAttemptsException(long retryAfterSeconds)
    {
        super(message(retryAfterSeconds));

        this.retryAfterSeconds = retryAfterSeconds;
    }


    // The header says the exact seconds (plus one of safety); the sentence
    // rounds the real wait up to whole minutes.
    private static String message(long retryAfterSeconds)
    {
        long minutes = Math.max(1, (retryAfterSeconds - 1 + 59) / 60);

        return "Too many failed attempts. Please try again in "
                + minutes
                + (minutes == 1 ? " minute." : " minutes.");
    }


    public long getRetryAfterSeconds()
    {
        return retryAfterSeconds;
    }
}

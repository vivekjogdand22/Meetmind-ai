package com.meetmind.backend.exception;

public class PlanConflictException extends RuntimeException
{
    public PlanConflictException(String message)
    {
        super(message);
    }
}

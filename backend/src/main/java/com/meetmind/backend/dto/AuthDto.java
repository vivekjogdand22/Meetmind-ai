package com.meetmind.backend.dto;


// What the login and register endpoints receive and send.
public class AuthDto
{
    private AuthDto()
    {
    }


    public record RegisterRequest(
            String email,
            String password,
            String name)
    {
    }


    public record LoginRequest(
            String email,
            String password)
    {
    }


    // Safe to show: never the password or its hash.
    public record UserInfo(
            Long id,
            String email,
            String name)
    {
    }


    // "token" goes into the header  Authorization: Bearer <token>  of every
    // later request. It stops working after "expiresInSeconds".
    public record AuthResponse(
            String token,
            long expiresInSeconds,
            UserInfo user)
    {
    }
}

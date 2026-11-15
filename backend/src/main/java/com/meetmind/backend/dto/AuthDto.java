package com.meetmind.backend.dto;


// What the account endpoints receive and send.
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


    // Forgot the password: the email and the recovery code that was shown
    // when the account (or the last recovery code) was created.
    public record ResetPasswordRequest(
            String email,
            String recoveryCode,
            String newPassword)
    {
    }


    public record ChangePasswordRequest(
            String currentPassword,
            String newPassword)
    {
    }


    // Asking for a new recovery code needs the password.
    public record RecoveryCodeRequest(
            String password)
    {
    }


    public record RecoveryCodeResponse(
            String recoveryCode)
    {
    }


    // Safe to show: never the password, its hash or the recovery code.
    public record UserInfo(
            Long id,
            String email,
            String name,
            boolean hasRecoveryCode)
    {
    }


    // "token" goes into the header  Authorization: Bearer <token>  of every
    // later request. It stops working after "expiresInSeconds", or as soon as
    // the password is changed.
    //
    // "recoveryCode" is filled in ONLY when a new one was just made (when the
    // account was created or the password was reset). It is shown once and
    // cannot be read again.
    public record AuthResponse(
            String token,
            long expiresInSeconds,
            UserInfo user,
            String recoveryCode)
    {
    }
}

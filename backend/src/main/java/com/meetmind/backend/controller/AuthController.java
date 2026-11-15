package com.meetmind.backend.controller;

import com.meetmind.backend.dto.AuthDto;
import com.meetmind.backend.security.CurrentUser;
import com.meetmind.backend.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;


// Creating an account, logging in, and the password. Register, login and
// reset-password are open to everybody; every other endpoint needs the token
// that they return.
@RestController
@Tag(name = "Accounts", description = "Create an account, log in, reset or change the password")
@RequestMapping("/api/auth")
public class AuthController
{
    private final AuthService authService;


    public AuthController(AuthService authService)
    {
        this.authService = authService;
    }


    @Operation(summary = "Create an account and log in (the answer holds your one-time recovery code)")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthDto.AuthResponse register(
            @RequestBody AuthDto.RegisterRequest request)
    {
        return authService.register(request);
    }


    @Operation(summary = "Log in with email and password (too many wrong tries lock the email for a while)")
    @PostMapping("/login")
    public AuthDto.AuthResponse login(
            @RequestBody AuthDto.LoginRequest request,
            HttpServletRequest http)
    {
        return authService.login(request, http.getRemoteAddr());
    }


    @Operation(summary = "Forgot the password: set a new one with the recovery code")
    @PostMapping("/reset-password")
    public AuthDto.AuthResponse resetPassword(
            @RequestBody AuthDto.ResetPasswordRequest request,
            HttpServletRequest http)
    {
        return authService.resetPassword(request, http.getRemoteAddr());
    }


    @Operation(summary = "Change the password (ends all other logins)")
    @PostMapping("/change-password")
    public AuthDto.AuthResponse changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody AuthDto.ChangePasswordRequest request)
    {
        return authService.changePassword(CurrentUser.id(jwt), request);
    }


    @Operation(summary = "Make a new one-time recovery code (the old one stops working)")
    @PostMapping("/recovery-code")
    public AuthDto.RecoveryCodeResponse recoveryCode(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody AuthDto.RecoveryCodeRequest request)
    {
        return authService.createRecoveryCode(CurrentUser.id(jwt), request);
    }


    @Operation(summary = "Who am I? (also checks that the token still works)")
    @GetMapping("/me")
    public AuthDto.UserInfo me(
            @AuthenticationPrincipal Jwt jwt)
    {
        return authService.me(CurrentUser.id(jwt));
    }
}

package com.meetmind.backend.controller;

import com.meetmind.backend.dto.AuthDto;
import com.meetmind.backend.security.CurrentUser;
import com.meetmind.backend.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;


// Creating an account and logging in. These two are open to everybody; every
// other endpoint needs the token that they return.
@RestController
@Tag(name = "Accounts", description = "Create an account, log in, see who you are")
@RequestMapping("/api/auth")
public class AuthController
{
    private final AuthService authService;


    public AuthController(AuthService authService)
    {
        this.authService = authService;
    }


    @Operation(summary = "Create an account and log in")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthDto.AuthResponse register(
            @RequestBody AuthDto.RegisterRequest request)
    {
        return authService.register(request);
    }


    @Operation(summary = "Log in with email and password")
    @PostMapping("/login")
    public AuthDto.AuthResponse login(
            @RequestBody AuthDto.LoginRequest request)
    {
        return authService.login(request);
    }


    @Operation(summary = "Who am I? (also checks that the token still works)")
    @GetMapping("/me")
    public AuthDto.UserInfo me(
            @AuthenticationPrincipal Jwt jwt)
    {
        return authService.me(CurrentUser.id(jwt));
    }
}

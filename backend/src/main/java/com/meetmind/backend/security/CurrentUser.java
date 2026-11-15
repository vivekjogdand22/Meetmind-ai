package com.meetmind.backend.security;

import org.springframework.security.oauth2.jwt.Jwt;

// Who is asking: the login token carries the user's id as its subject.
public final class CurrentUser
{
    private CurrentUser()
    {
    }

    public static Long id(Jwt jwt)
    {
        return Long.valueOf(jwt.getSubject());
    }
}

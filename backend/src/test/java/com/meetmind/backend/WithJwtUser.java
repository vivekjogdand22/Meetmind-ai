package com.meetmind.backend;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;


// Runs a test as a logged-in user, without going through the login screen:
//
//     @WithJwtUser                 acts as user 1
//     @WithJwtUser(userId = 7)     acts as user 7
//
// The meetings that such a test creates must be saved with the same owner id.

@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithJwtUser.Factory.class)
public @interface WithJwtUser
{
    long DEFAULT_USER_ID = 1;

    long userId() default DEFAULT_USER_ID;


    class Factory implements WithSecurityContextFactory<WithJwtUser>
    {
        @Override
        public SecurityContext createSecurityContext(WithJwtUser annotation)
        {
            Jwt jwt =
                    Jwt.withTokenValue("test-token")
                            .header("alg", "HS256")
                            .subject(String.valueOf(annotation.userId()))
                            .build();

            SecurityContext context = SecurityContextHolder.createEmptyContext();

            context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

            return context;
        }
    }
}

package com.meetmind.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;


// A person who can log in. The password is never stored, only its BCrypt hash.
// (The table is called app_users because "user" is reserved in PostgreSQL.)

@Entity
@Table(name = "app_users")
public class User
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String passwordHash;

    private Instant createdAt = Instant.now();

    // BCrypt hash of the one-time recovery code (never the code itself).
    // Empty for accounts that never asked for one.
    private String recoveryCodeHash;

    // Goes up whenever the password changes. Login tokens carry the number
    // they were issued with, so changing the password ends every other login.
    @Column(columnDefinition = "integer default 0 not null")
    private int tokenVersion = 0;


    public Long getId()
    {
        return id;
    }

    public String getEmail()
    {
        return email;
    }

    public void setEmail(String email)
    {
        this.email = email;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    public String getPasswordHash()
    {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash)
    {
        this.passwordHash = passwordHash;
    }

    public Instant getCreatedAt()
    {
        return createdAt;
    }


    public String getRecoveryCodeHash()
    {
        return recoveryCodeHash;
    }

    public void setRecoveryCodeHash(String recoveryCodeHash)
    {
        this.recoveryCodeHash = recoveryCodeHash;
    }

    public int getTokenVersion()
    {
        return tokenVersion;
    }

    public void setTokenVersion(int tokenVersion)
    {
        this.tokenVersion = tokenVersion;
    }
}

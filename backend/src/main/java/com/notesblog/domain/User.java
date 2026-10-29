package com.notesblog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "role_name", nullable = false)
    private String role = "ADMIN";

    /** 令牌版本号：改密码时 +1，使此前签发的所有 JWT 立即失效。 */
    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}

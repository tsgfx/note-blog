package com.notesblog.config;

import com.notesblog.service.JwtService;
import com.notesblog.service.TokenVersionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final TokenVersionService tokenVersionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            // 角色取自令牌本身，不一律授予 ROLE_ADMIN；
            // 版本号还要与库中比对，改密码后旧令牌立即失效。
            jwtService.verify(header.substring(7))
                    .filter(principal -> tokenVersionService.isCurrent(
                            principal.username(), principal.tokenVersion()))
                    .ifPresentOrElse(
                            principal -> SecurityContextHolder.getContext().setAuthentication(
                                    new UsernamePasswordAuthenticationToken(principal.username(), null,
                                            List.of(new SimpleGrantedAuthority(
                                                    "ROLE_" + principal.role().toUpperCase(Locale.ROOT))))),
                            () -> log.debug("令牌无效或已失效 path={}", request.getRequestURI()));
        }
        filterChain.doFilter(request, response);
    }
}

package com.notesblog.service;

import com.notesblog.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 令牌版本号：配合 JWT 里的 {@code tv} claim 实现"改密码后旧令牌立即失效"。
 *
 * <p>用单调计数器而不是"密码修改时间"：JWT 的 {@code iat} 与 DATETIME 都只精确到秒，
 * 同一秒内改密与签发新令牌无法区分，需要 ±1s 补偿；计数器没有这个问题，也不受时区影响。
 */
@Service
@RequiredArgsConstructor
public class TokenVersionService {

    private final UserRepository userRepository;

    /** 令牌里的版本号是否与库中一致。用户不存在时视为不一致。 */
    @Transactional(readOnly = true)
    public boolean isCurrent(String username, long tokenVersion) {
        return userRepository.findTokenVersionByUsername(username)
                .map(current -> current == tokenVersion)
                .orElse(false);
    }

    /** 吊销该用户的全部已签发令牌。 */
    @Transactional
    public void revokeAll(String username) {
        userRepository.bumpTokenVersion(username);
    }
}

package com.notesblog.repository;

import com.notesblog.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    /**
     * 只取令牌版本号。用投影查询而不是 findByUsername：
     * 每个已认证请求都会走这里，没必要把 password_hash 读出来，也不该让实体进持久化上下文。
     */
    @Query("select u.tokenVersion from User u where u.username = :username")
    Optional<Long> findTokenVersionByUsername(@Param("username") String username);

    /** 令牌版本号自增，等价于吊销该用户的全部已签发令牌。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.tokenVersion = u.tokenVersion + 1 where u.username = :username")
    int bumpTokenVersion(@Param("username") String username);
}

package com.myide.backend.repository.message;

import com.myide.backend.domain.User;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DirectMessageUserRepository
        extends JpaRepository<User, Long> {


    /*
     * ==========================================
     * 두 사용자 row lock
     *
     * ID 순서대로 lock해서
     * deadlock 가능성 감소
     * ==========================================
     */

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u
            from User u
            where u.id in :ids
            order by u.id
            """)
    List<User> findAllByIdInForUpdate(
            @Param("ids")
            Collection<Long> ids
    );
}
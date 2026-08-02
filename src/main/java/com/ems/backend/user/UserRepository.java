package com.ems.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    @Query("select user from User user where lower(trim(user.email)) = lower(trim(:email))")
    Optional<User> findByEmail(@Param("email") String email);

    @Query("""
            select user.id as id, user.email as email, user.role as role,
                   user.active as active, user.securityVersion as securityVersion
            from User user
            where lower(trim(user.email)) = lower(trim(:email))
            """)
    Optional<AuthenticationStateRow> findAuthenticationStateByEmail(@Param("email") String email);
    Optional<User> findByEmailVerificationTokenHash(String tokenHash);
    Optional<User> findByPasswordResetTokenHash(String tokenHash);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPendingEmailIgnoreCase(String email);
    Optional<User> findByEmployeeIdIgnoreCase(String employeeId);
    Optional<User> findByProfileMediaAssetId(UUID mediaAssetId);
    List<User> findByRoleAndActiveTrueOrderByFullNameAsc(Role role);
    Page<User> findByRoleAndActiveTrue(Role role, Pageable pageable);

    @Query("""
            select scope.employee from ManagerEmployeeScope scope
            where scope.manager.id = :managerId and scope.active = true
              and scope.employee.active = true
            order by lower(scope.employee.fullName)
            """)
    List<User> findActiveManagedEmployees(@Param("managerId") Long managerId);

    @Query("""
            select scope.employee from ManagerEmployeeScope scope
            where scope.manager.id = :managerId and scope.active = true
              and scope.employee.active = true
            """)
    Page<User> findActiveManagedEmployees(@Param("managerId") Long managerId, Pageable pageable);

    @Query("""
            select user from User user
            where user.id = :managerId or exists (
                select scope.id from ManagerEmployeeScope scope
                where scope.manager.id = :managerId
                  and scope.employee.id = user.id
                  and scope.active = true
                  and user.active = true
            )
            """)
    Page<User> findVisibleDirectoryToManager(@Param("managerId") Long managerId, Pageable pageable);

    @Query("""
            select user from User user
            where user.active = true and user.role <> com.ems.backend.user.Role.ADMIN
            order by lower(user.fullName)
            """)
    List<User> findAllActiveNonAdmin();

    @Query("select user from User user where user.active = true and user.role <> com.ems.backend.user.Role.ADMIN")
    Page<User> findAllActiveNonAdmin(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where lower(trim(user.email)) = lower(trim(:email))")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.passwordResetTokenHash = :tokenHash")
    Optional<User> findByPasswordResetTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.emailVerificationTokenHash = :tokenHash")
    Optional<User> findByEmailVerificationTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User user set user.securityVersion = user.securityVersion + 1 where user.id = :id")
    int incrementSecurityVersion(@Param("id") Long id);

    interface AuthenticationStateRow {
        Long getId();
        String getEmail();
        Role getRole();
        Boolean getActive();
        Integer getSecurityVersion();
    }
}

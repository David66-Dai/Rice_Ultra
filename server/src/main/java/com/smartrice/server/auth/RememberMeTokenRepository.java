package com.smartrice.server.auth;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RememberMeTokenRepository extends JpaRepository<RememberMeToken, String> {

	@Modifying
	@Query("delete from RememberMeToken t where t.user.id = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

	@Modifying
	@Query("delete from RememberMeToken t where t.expiresAt < :now")
	int deleteAllExpiredBefore(@Param("now") Instant now);
}

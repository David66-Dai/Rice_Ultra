package com.smartrice.server.notifications;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, Long> {

	List<NotificationEvent> findTop100ByOrderByIdDesc();

	long countByIdGreaterThan(long id);

	@Query("select coalesce(max(n.id), 0) from NotificationEvent n")
	long latestId();

	List<NotificationEvent> findByTypeAndSourceIdIn(String type, Collection<Long> sourceIds);
}

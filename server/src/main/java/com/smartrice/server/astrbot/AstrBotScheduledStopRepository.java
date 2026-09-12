package com.smartrice.server.astrbot;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AstrBotScheduledStopRepository extends JpaRepository<AstrBotScheduledStop, String> {

	List<AstrBotScheduledStop> findByStatusIn(Collection<String> statuses);
}

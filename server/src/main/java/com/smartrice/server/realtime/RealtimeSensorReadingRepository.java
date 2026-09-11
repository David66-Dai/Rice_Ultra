package com.smartrice.server.realtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RealtimeSensorReadingRepository extends JpaRepository<RealtimeSensorReading, Long> {

	List<RealtimeSensorReading> findByStationIdAndSampledAtGreaterThanEqualAndSampledAtLessThanOrderBySampledAtAsc(
		String stationId, Instant start, Instant end
	);

	Optional<RealtimeSensorReading> findFirstByStationIdOrderBySampledAtDesc(String stationId);
}

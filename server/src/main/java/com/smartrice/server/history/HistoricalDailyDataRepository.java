package com.smartrice.server.history;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistoricalDailyDataRepository extends JpaRepository<HistoricalDailyData, Long> {

	Optional<HistoricalDailyData> findByStationIdAndRecordDate(String stationId, LocalDate recordDate);

	Optional<HistoricalDailyData> findFirstByStationIdAndRecordDateLessThanEqualOrderByRecordDateAsc(
		String stationId, LocalDate maxDate
	);

	Optional<HistoricalDailyData> findFirstByStationIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
		String stationId, LocalDate maxDate
	);

	long countByStationIdAndRecordDateLessThanEqual(String stationId, LocalDate maxDate);
}

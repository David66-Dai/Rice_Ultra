package com.smartrice.server.realtime;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationDeviceRepository extends JpaRepository<StationDevice, Long> {

	Optional<StationDevice> findByStationIdAndDevice(String stationId, String device);
}

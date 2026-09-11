package com.smartrice.server.realtime;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RedisPendingSampleRepository extends JpaRepository<RedisPendingSample, String> {
	List<RedisPendingSample> findTop100ByOrderBySampledAtAscIdAsc();
}

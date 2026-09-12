package com.smartrice.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "app.auth.bootstrap-admin.enabled=false")
class SmartRiceServerApplicationTests {

	@Test
	void contextLoads() {
	}

}

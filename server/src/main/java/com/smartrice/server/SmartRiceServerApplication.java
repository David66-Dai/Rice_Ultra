package com.smartrice.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartRiceServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmartRiceServerApplication.class, args);
	}

}

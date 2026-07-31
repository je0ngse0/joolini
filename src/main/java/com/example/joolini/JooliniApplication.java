package com.example.joolini;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JooliniApplication {

	public static void main(String[] args) {
		SpringApplication.run(JooliniApplication.class, args);
	}

}

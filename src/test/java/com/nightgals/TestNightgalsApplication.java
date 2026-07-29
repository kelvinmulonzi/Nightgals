package com.nightgals;

import org.springframework.boot.SpringApplication;

public class TestNightgalsApplication {

	public static void main(String[] args) {
		SpringApplication.from(NightgalsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

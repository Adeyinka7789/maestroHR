package com.admtechhub.maestrohr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;


@EnableAsync
@SpringBootApplication
public class MaestrohrApplication {

	public static void main(String[] args) {
		SpringApplication.run(MaestrohrApplication.class, args);
	}

}

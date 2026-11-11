package com.likelion4thon.Meetdle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class MeetdleApplication {

	public static void main(String[] args) {
		SpringApplication.run(MeetdleApplication.class, args);
	}

}

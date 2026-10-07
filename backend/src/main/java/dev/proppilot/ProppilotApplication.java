package dev.proppilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProppilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProppilotApplication.class, args);
    }
}

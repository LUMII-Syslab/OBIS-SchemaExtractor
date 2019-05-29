package lv.lumii.obis.rest.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Main entry point for OBIS Schema Extractor REST application.
 * Spring Boot Maven plugin searches for this "main" method and flags it as a runnable class.
 * It collects all the jars on the classpath and builds a single executable jar.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"lv.lumii.obis"})
public class SchemaExtractorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchemaExtractorApplication.class, args);
    }

}

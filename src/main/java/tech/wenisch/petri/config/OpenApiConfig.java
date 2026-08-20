package tech.wenisch.petri.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI petriOpenApi(@Value("${project.version:unknown}") String version) {
        return new OpenAPI().info(new Info()
                .title("Petri API")
                .description("Agent orchestrator")
                .version(version)
                .license(new License()
                        .name("AGPL-3.0")
                        .url("https://www.gnu.org/licenses/agpl-3.0.txt")));
    }
}

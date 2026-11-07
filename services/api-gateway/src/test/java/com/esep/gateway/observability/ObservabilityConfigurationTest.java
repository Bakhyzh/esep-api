package com.esep.gateway.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Check against metadata from the actual Boot dependency, including retired aliases. */
class ObservabilityConfigurationTest {
    @Test void usesSupportedPropertiesAndRequiresExplicitExporterOptIn() throws Exception {
        var metadata=new HashSet<String>();var retired=new HashSet<String>();
        var resources=getClass().getClassLoader().getResources("META-INF/spring-configuration-metadata.json");
        var mapper=new JsonMapper();
        while(resources.hasMoreElements()){
            try(var input=resources.nextElement().openStream()){
                var properties=mapper.readTree(input).path("properties");
                for(var property:properties){
                    String name=property.path("name").asString();metadata.add(name);
                    if(property.path("deprecation").path("level").asString("").equals("error"))retired.add(name);
                }
            }
        }
        var loader=new YamlPropertySourceLoader();
        var base=loader.load("base",new ClassPathResource("application.yaml")).getFirst();
        var optIn=loader.load("optIn",new ClassPathResource("application-observability.yaml")).getFirst();
        for(String property:List.of("management.tracing.export.enabled","management.tracing.export.otlp.enabled",
                "management.logging.export.otlp.enabled","management.opentelemetry.enabled")){
            assertThat(metadata).contains(property);
            assertThat(base.getProperty(property)).as(property+" default").isEqualTo(false);
            assertThat(optIn.getProperty(property)).as(property+" opt-in").isEqualTo(true);
        }
        assertThat(metadata).contains("management.opentelemetry.logging.export.max-batch-size");
        assertThat(optIn.getProperty("management.opentelemetry.logging.export.max-batch-size")).isEqualTo(64);
        for(String property:retired){
            assertThat(base.getProperty(property)).as("retired "+property).isNull();
            assertThat(optIn.getProperty(property)).as("retired "+property).isNull();
        }
    }
}

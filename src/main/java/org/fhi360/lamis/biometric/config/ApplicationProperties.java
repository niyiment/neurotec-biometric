package org.fhi360.lamis.biometric.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)
@Configuration
@Data
public class ApplicationProperties {
    private String serverUrl;
    private String libraryPath;
}

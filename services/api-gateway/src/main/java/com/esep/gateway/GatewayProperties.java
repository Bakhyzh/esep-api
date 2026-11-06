package com.esep.gateway;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.net.URI;
import java.util.List;
@Validated
@ConfigurationProperties("esep.gateway")
public record GatewayProperties(@NotNull URI userUri, @NotNull URI ledgerUri,
        @NotNull URI analyticsUri, @NotNull URI notificationUri,
        @NotEmpty List<String> corsOrigins, @NotBlank String issuerUri, @NotBlank String jwkSetUri,
        @Min(1) int readLimit, @Min(1) int writeLimit, @Min(1) int windowSeconds,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String instance) { }

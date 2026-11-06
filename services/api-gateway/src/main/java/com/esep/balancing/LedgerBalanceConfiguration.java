package com.esep.balancing;

import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;
import java.time.Duration;

/** Loaded only into Ledger's LoadBalancer child context, outside the application component scan. */
public class LedgerBalanceConfiguration {
    @Bean ServiceInstanceListSupplier ledgerInstances(ConfigurableApplicationContext context) {
        var http=HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS,500).responseTimeout(Duration.ofSeconds(1));
        var health=WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
        return ServiceInstanceListSupplier.builder().withDiscoveryClient().withHealthChecks(health).build(context);
    }
}

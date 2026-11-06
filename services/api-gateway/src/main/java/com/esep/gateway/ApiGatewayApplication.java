package com.esep.gateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient(name="ledger", configuration=com.esep.balancing.LedgerBalanceConfiguration.class)
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) { SpringApplication.run(ApiGatewayApplication.class, args); }
}

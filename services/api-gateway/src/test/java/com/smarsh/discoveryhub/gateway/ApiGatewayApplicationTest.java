package com.smarsh.discoveryhub.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real gateway context so a broken {@code application.yml} (e.g. a
 * duplicate-key merge artifact — SnakeYAML 2 rejects those at startup) fails
 * the build instead of the Day-3 demo. Requires the seven core service routes
 * to be present — additional routes (e.g. admin passthroughs) are allowed so
 * teammates can extend the table without breaking this test.
 */
@SpringBootTest
class ApiGatewayApplicationTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void contextLoadsAndRoutesAllSevenServices() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotNull();
        assertThat(routes).extracting(Route::getId).contains(
                "ingest", "archive", "search", "cases", "holds", "exports", "audit");
    }
}

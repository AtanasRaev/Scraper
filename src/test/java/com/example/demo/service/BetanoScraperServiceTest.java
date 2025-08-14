package com.example.demo.service;

import com.example.demo.config.ProxyConfig;
import com.example.demo.model.BettingEvent;
import com.example.demo.util.UserAgentRotator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class BetanoScraperServiceTest {

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/test", exchange -> {
            String html = "<html><body><script>fetch('/api/bettingoffer')</script></body></html>";
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, html.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html.getBytes());
            }
        });

        server.createContext("/api/bettingoffer", exchange -> {
            String json = "{\"events\":[{\"id\":\"1\",\"name\":\"Test\",\"startTime\":\"2024-01-01T00:00:00\",\"markets\":[]}]}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldCaptureEventsFromStubbedEndpoint() {
        ProxyConfig proxyConfig = new ProxyConfig();
        ObjectMapper objectMapper = new ObjectMapper();
        UserAgentRotator rotator = new UserAgentRotator();

        BetanoScraperService service = new BetanoScraperService(proxyConfig, objectMapper, rotator);
        List<BettingEvent> events = service.scrapeBettingData("http://localhost:" + port + "/test");

        assertFalse(events.isEmpty(), "Expected events to be captured from stubbed endpoint");
    }
}

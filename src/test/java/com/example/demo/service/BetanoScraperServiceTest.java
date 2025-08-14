package com.example.demo.service;

import com.example.demo.config.ProxyConfig;
import com.example.demo.model.BettingEvent;
import com.example.demo.model.BettingMarket;
import com.example.demo.model.BettingSelection;
import com.example.demo.util.UserAgentRotator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void shouldParseBetslipJson() throws Exception {
        ProxyConfig proxyConfig = new ProxyConfig();
        ObjectMapper objectMapper = new ObjectMapper();
        UserAgentRotator rotator = new UserAgentRotator();

        BetanoScraperService service = new BetanoScraperService(proxyConfig, objectMapper, rotator);

        String json = "{\"bets\":[{\"legs\":[{\"event\":{\"id\":\"1\",\"name\":\"Match\",\"startTime\":\"2024-01-01T00:00:00\"},\"market\":{\"id\":\"m1\",\"name\":\"Winner\"},\"selection\":{\"id\":\"s1\",\"name\":\"Team A\",\"price\":{\"decimal\":1.5}}}]}]}";
        JsonNode node = objectMapper.readTree(json);

        List<BettingEvent> events = service.parseBetslipJson(node);

        assertEquals(1, events.size());
        BettingEvent event = events.get(0);
        assertEquals("1", event.getEventId());
        assertEquals("Match", event.getMatchName());
        assertEquals(1, event.getMarkets().size());
        BettingMarket market = event.getMarkets().get(0);
        assertEquals("m1", market.getMarketId());
        assertEquals("Winner", market.getMarketType());
        assertEquals(1, market.getSelections().size());
        BettingSelection sel = market.getSelections().get(0);
        assertEquals("s1", sel.getSelectionId());
        assertEquals("Team A", sel.getSelectionName());
        assertEquals(1.5, sel.getOdds());
    }
}

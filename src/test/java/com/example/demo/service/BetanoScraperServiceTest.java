package com.example.demo.service;

import com.example.demo.config.ProxyConfig;
import com.example.demo.model.BettingEvent;
import com.example.demo.util.UserAgentRotator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BetanoScraperServiceTest {

    @Test
    void parseEventsFromJson_mapsFieldsCorrectly() throws Exception {
        String json = "{\"events\":[{\"id\":\"evt1\",\"name\":\"Team A vs Team B\",\"startTime\":\"2025-08-14T20:00:00\",\"markets\":[{\"id\":\"mkt1\",\"name\":\"Match Winner\",\"selections\":[{\"id\":\"sel1\",\"name\":\"Team A\",\"odds\":1.5},{\"id\":\"sel2\",\"name\":\"Draw\",\"odds\":3.4},{\"id\":\"sel3\",\"name\":\"Team B\",\"odds\":2.8}]}]}]}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);

        BetanoScraperService service = new BetanoScraperService(new ProxyConfig(), mapper, new UserAgentRotator());

        List<BettingEvent> events = service.parseEventsFromJson(node, "evt1");

        assertEquals(1, events.size());
        BettingEvent event = events.get(0);
        assertEquals("evt1", event.getEventId());
        assertEquals("Team A vs Team B", event.getMatchName());
        assertEquals(1, event.getMarkets().size());
        assertEquals("mkt1", event.getMarkets().get(0).getMarketId());
        assertEquals(3, event.getMarkets().get(0).getSelections().size());
        assertEquals("sel1", event.getMarkets().get(0).getSelections().get(0).getSelectionId());
        assertEquals(1.5, event.getMarkets().get(0).getSelections().get(0).getOdds(), 0.0001);
    }

    @Test
    void buildMatchUrl_resolvesNumericIdViaApi() {
        ObjectMapper mapper = new ObjectMapper();

        BetanoScraperService service = new BetanoScraperService(new ProxyConfig(), mapper, new UserAgentRotator()) {
            @Override
            String resolveMatchPath(String matchId) {
                // Simulate API lookup returning a slug for numeric IDs
                if ("123".equals(matchId)) {
                    return "sport/football/match-slug";
                }
                return super.resolveMatchPath(matchId);
            }
        };

        String url = service.buildMatchUrl("123");
        assertEquals("https://www.betano.bg/sport/football/match-slug", url);
    }

    @Test
    void buildMatchUrl_usesSlugDirectly() {
        ObjectMapper mapper = new ObjectMapper();
        BetanoScraperService service = new BetanoScraperService(new ProxyConfig(), mapper, new UserAgentRotator());

        String url = service.buildMatchUrl("sport/football/match-slug");
        assertEquals("https://www.betano.bg/sport/football/match-slug", url);
    }
}

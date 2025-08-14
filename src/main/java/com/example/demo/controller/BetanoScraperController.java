package com.example.demo.controller;

import com.example.demo.model.BettingEvent;
import com.example.demo.service.BetanoScraperService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for the Betano scraper
 */
@RestController
@RequestMapping("/api/scraper")
@RequiredArgsConstructor
@Slf4j
public class BetanoScraperController {

    private final BetanoScraperService scraperService;

    /**
     * Triggers the scraping process and returns the results
     * @return List of betting events with markets and selections
     */
    @GetMapping("/betano")
    public ResponseEntity<List<BettingEvent>> scrapeBetano() {
        log.info("Received request to scrape Betano");
        List<BettingEvent> events = scraperService.scrapeBettingData();
        log.info("Scraping completed, returning {} events", events.size());
        return ResponseEntity.ok(events);
    }
}
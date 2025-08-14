package com.example.demo.controller;

import com.example.demo.model.BettingEvent;
import com.example.demo.service.EfbetScraperService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for the Efbet scraper.
 */
@RestController
@RequestMapping("/api/scraper")
@RequiredArgsConstructor
@Slf4j
public class EfbetScraperController {

    private final EfbetScraperService scraperService;

    /**
     * Triggers the Efbet scraping process and returns the results.
     *
     * @return list of betting events with markets and selections
     */
    @GetMapping("/efbet")
    public ResponseEntity<List<BettingEvent>> scrapeEfbet() {
        log.info("Received request to scrape Efbet");
        List<BettingEvent> events = scraperService.scrapeBettingData();
        log.info("Scraping completed, returning {} events", events.size());
        return ResponseEntity.ok(events);
    }
}

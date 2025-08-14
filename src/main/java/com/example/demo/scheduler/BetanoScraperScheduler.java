package com.example.demo.scheduler;

import com.example.demo.model.BettingEvent;
import com.example.demo.service.BetanoScraperService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Scheduler for periodic scraping of Betano.bg
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class BetanoScraperScheduler {

    private final BetanoScraperService scraperService;
    private final ObjectMapper objectMapper;
    
    @Value("${scraper.output.enabled:false}")
    private boolean outputEnabled;
    
    @Value("${scraper.output.directory:./scraper-output}")
    private String outputDirectory;

    @Value("${scraper.matchId:}")
    private String matchId;
    
    /**
     * Runs the scraper every 45 seconds (configurable)
     * This interval can be adjusted based on the requirements
     */
    @Scheduled(fixedRateString = "${scraper.interval:45000}")
    public void scheduledScraping() {
        log.info("Starting scheduled scraping task");
        
        try {
            List<BettingEvent> events = scraperService.scrapeBettingData(matchId);
            log.info("Scheduled scraping completed, found {} events", events.size());
            
            if (outputEnabled && !events.isEmpty()) {
                saveToFile(events);
            }
        } catch (Exception e) {
            log.error("Error during scheduled scraping: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Saves the scraped data to a JSON file
     */
    private void saveToFile(List<BettingEvent> events) {
        try {
            File directory = new File(outputDirectory);
            if (!directory.exists()) {
                directory.mkdirs();
            }
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File outputFile = new File(directory, "betano_odds_" + timestamp + ".json");
            
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, events);
            log.info("Saved scraping results to {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Error saving scraping results to file: {}", e.getMessage(), e);
        }
    }
}
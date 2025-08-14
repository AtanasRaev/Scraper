package com.example.demo.scheduler;

import com.example.demo.model.BettingEvent;
import com.example.demo.service.EfbetScraperService;
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
 * Scheduler for periodic scraping of Efbet.
 */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class EfbetScraperScheduler {

    private final EfbetScraperService scraperService;
    private final ObjectMapper objectMapper;

    @Value("${efbet.scraper.output.enabled:false}")
    private boolean outputEnabled;

    @Value("${efbet.scraper.output.directory:./scraper-output}")
    private String outputDirectory;

    /**
     * Runs the Efbet scraper on a fixed interval defined by
     * {@code efbet.scraper.interval} property. Default is every 60 seconds.
     */
    @Scheduled(fixedRateString = "${efbet.scraper.interval:60000}")
    public void scheduledScraping() {
        log.info("Starting scheduled Efbet scraping task");
        try {
            List<BettingEvent> events = scraperService.scrapeBettingData();
            log.info("Scheduled Efbet scraping completed, found {} events", events.size());

            if (outputEnabled && !events.isEmpty()) {
                saveToFile(events);
            }
        } catch (Exception e) {
            log.error("Error during scheduled Efbet scraping: {}", e.getMessage(), e);
        }
    }

    private void saveToFile(List<BettingEvent> events) {
        try {
            File directory = new File(outputDirectory);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File outputFile = new File(directory, "efbet_odds_" + timestamp + ".json");

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, events);
            log.info("Saved Efbet scraping results to {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Error saving Efbet scraping results to file: {}", e.getMessage(), e);
        }
    }
}

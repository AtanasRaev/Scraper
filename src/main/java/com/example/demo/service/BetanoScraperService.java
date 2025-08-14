package com.example.demo.service;

import com.example.demo.config.ProxyConfig;
import com.example.demo.model.BettingEvent;
import com.example.demo.model.BettingMarket;
import com.example.demo.model.BettingSelection;
import com.example.demo.util.UserAgentRotator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Proxy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class BetanoScraperService {

    private final ProxyConfig proxyConfig;
    private final ObjectMapper objectMapper;
    private final UserAgentRotator userAgentRotator;

    private static final String BETANO_URL = "https://www.betano.bg/sports";
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 5000;

    // Flag to track if we should try without proxy due to connection issues
    private boolean tryWithoutProxy = false;

    /**
     * Scrapes betting data from Betano.bg for a specific match
     *
     * @param matchId identifier or URL segment for the match
     * @return List of betting events with markets and selections
     */
    public List<BettingEvent> scrapeBettingData(String matchId) {
        log.info("Starting Betano scraping process for match: {}", matchId);
        List<BettingEvent> events = new ArrayList<>();

        // Build a match specific URL if an identifier is provided
        String matchUrl = BETANO_URL;
        if (matchId != null && !matchId.isBlank()) {
            matchUrl = BETANO_URL + "/" + matchId;
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);

            try (BrowserContext context = createBrowserContext(browser)) {
                Page page = context.newPage();

                // Set up request interception for XHR/JSON API requests related to the match
                String apiPattern = ".*api.*" + (matchId != null ? matchId : "") + ".*";
                page.route(Pattern.compile(apiPattern), route -> {
                    log.debug("Intercepted API request: {}", route.request().url());
                    route.resume();
                });

                // Set up response handling for intercepted requests
                page.onResponse(response -> {
                    String url = response.url();
                    if (matchId == null || url.contains(matchId)) {
                        try {
                            log.debug("Processing response from: {}", url);
                            String responseBody = response.text();
                            JsonNode jsonNode = objectMapper.readTree(responseBody);
                            List<BettingEvent> parsedEvents = parseEventsFromJson(jsonNode, matchId);
                            events.addAll(parsedEvents);
                        } catch (Exception e) {
                            log.error("Error processing response: {}", e.getMessage(), e);
                        }
                    }
                });

                // Navigate to Betano and wait for the page to load
                log.info("Navigating to {}", matchUrl);
                page.navigate(matchUrl);
                page.waitForLoadState(LoadState.NETWORKIDLE);

                // Add a delay to ensure all API requests are intercepted
                page.waitForTimeout(5000);

                log.info("Scraping completed successfully");
            } catch (Exception e) {
                log.error("Error during scraping: {}", e.getMessage(), e);
            }
        }

        return events;
    }

    /**
     * Launches a headless browser with retry logic
     */
    private Browser launchBrowser(Playwright playwright) {
        int retries = 0;
        // Reset the tryWithoutProxy flag at the start of each scraping session
        tryWithoutProxy = false;

        while (retries < MAX_RETRIES) {
            try {
                BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setSlowMo(100);

                // Add proxy at browser launch level if enabled and not in fallback mode
                String proxyUrl = proxyConfig.getProxyUrl();
                if (proxyUrl != null && !tryWithoutProxy) {
                    log.info("Setting global proxy at browser launch: {}", proxyUrl);
                    launchOptions.setProxy(new Proxy(proxyUrl));
                } else if (tryWithoutProxy) {
                    log.info("Trying to launch browser without proxy after previous failures");
                }

                log.info("Launching headless browser");
                return playwright.chromium().launch(launchOptions);
            } catch (Exception e) {
                retries++;
                log.warn("Failed to launch browser (attempt {}/{}): {}", 
                        retries, MAX_RETRIES, e.getMessage());

                // If we've tried once with proxy and failed, try without proxy on the next attempt
                if (retries == 1 && proxyConfig.getProxyUrl() != null && !tryWithoutProxy) {
                    tryWithoutProxy = true;
                    log.info("Proxy connection failed. Will try without proxy on next attempt.");
                }

                if (retries >= MAX_RETRIES) {
                    log.error("Max retries reached. Unable to launch browser.", e);
                    throw new RuntimeException("Failed to launch browser after " + MAX_RETRIES + " attempts", e);
                }

                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        throw new RuntimeException("Failed to launch browser");
    }

    /**
     * Creates a browser context with proxy configuration if enabled
     * and a random user agent for stealth
     */
    private BrowserContext createBrowserContext(Browser browser) {
        // Get a random user agent for stealth
        String userAgent = userAgentRotator.getRandomUserAgent();
        log.info("Using user agent: {}", userAgent);

        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                .setUserAgent(userAgent)
                .setViewportSize(1920, 1080)
                .setIgnoreHTTPSErrors(true);

        // Add proxy if enabled and not in fallback mode
        String proxyUrl = proxyConfig.getProxyUrl();
        if (proxyUrl != null && !tryWithoutProxy) {
            log.info("Using proxy in browser context: {}", proxyUrl);
            contextOptions.setProxy(new Proxy(proxyUrl));
        } else if (tryWithoutProxy) {
            log.info("Creating browser context without proxy (in fallback mode)");
        } else {
            log.info("No proxy configured");
        }

        return browser.newContext(contextOptions);
    }

    /**
     * Parses betting events from JSON response and optionally filters by matchId
     */
    private List<BettingEvent> parseEventsFromJson(JsonNode jsonNode, String matchId) {
        List<BettingEvent> events = new ArrayList<>();

        try {
            // This is a placeholder implementation
            // The actual parsing logic will depend on the structure of Betano's API responses
            // You'll need to adapt this based on the actual JSON structure

            if (jsonNode.has("events") && jsonNode.get("events").isArray()) {
                JsonNode eventsNode = jsonNode.get("events");

                for (JsonNode eventNode : eventsNode) {
                    String id = eventNode.path("id").asText();
                    String urlSegment = eventNode.path("url").asText();

                    if (matchId != null && !matchId.isBlank()) {
                        boolean matchesId = matchId.equals(id);
                        boolean matchesUrl = urlSegment != null && urlSegment.contains(matchId);
                        if (!matchesId && !matchesUrl) {
                            continue; // skip events that do not match the requested id
                        }
                    }

                    String matchName = eventNode.path("name").asText();
                    String startTimeStr = eventNode.path("startTime").asText();
                    LocalDateTime startTime = parseDateTime(startTimeStr);

                    List<BettingMarket> markets = new ArrayList<>();

                    if (eventNode.has("markets") && eventNode.get("markets").isArray()) {
                        JsonNode marketsNode = eventNode.get("markets");

                        for (JsonNode marketNode : marketsNode) {
                            String marketType = marketNode.path("type").asText();
                            List<BettingSelection> selections = new ArrayList<>();

                            if (marketNode.has("selections") && marketNode.get("selections").isArray()) {
                                JsonNode selectionsNode = marketNode.get("selections");

                                for (JsonNode selectionNode : selectionsNode) {
                                    String selectionName = selectionNode.path("name").asText();
                                    double odds = selectionNode.path("odds").asDouble();

                                    selections.add(BettingSelection.builder()
                                            .selectionName(selectionName)
                                            .odds(odds)
                                            .build());
                                }
                            }

                            markets.add(BettingMarket.builder()
                                    .marketType(marketType)
                                    .selections(selections)
                                    .build());
                        }
                    }

                    events.add(BettingEvent.builder()
                            .matchName(matchName)
                            .startTime(startTime)
                            .markets(markets)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Error parsing JSON response: {}", e.getMessage(), e);
        }

        return events;
    }

    /**
     * Parses date-time string to LocalDateTime
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            // This is a placeholder implementation
            // The actual parsing logic will depend on the format of Betano's date-time strings
            return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e) {
            log.warn("Failed to parse date-time: {}", dateTimeStr);
            return LocalDateTime.now();
        }
    }
}

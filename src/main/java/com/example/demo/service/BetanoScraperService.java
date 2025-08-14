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
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class BetanoScraperService {

    private final ProxyConfig proxyConfig;
    private final ObjectMapper objectMapper;
    private final UserAgentRotator userAgentRotator;

    private static final String BETANO_URL = "https://www.betano.bg";
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

                // Intercept all network requests during debugging. Previously the
                // scraper filtered only specific betting endpoints ("offer",
                // "events", "betting"), which made it easy to miss additional APIs
                // such as "api/events", "live-data" or GraphQL endpoints. Using a
                // catch-all pattern allows us to review every request and later narrow
                // the filter once the real API paths are confirmed via browser
                // inspection.
                String apiPattern = ".*"; // capture all requests for investigation
                page.route(Pattern.compile(apiPattern), route -> {
                    log.debug("Intercepted API request: {}", route.request().url());
                    route.resume();
                });

                // Set up response handling for intercepted requests
                page.onResponse(response -> {
                    String url = response.url();
                    int status = response.status();
                    String contentType = response.headers().get("content-type");
                    String resourceType = response.request().resourceType();

                    boolean isSuccessful = status >= 200 && status < 300;
                    boolean isJson = contentType != null && contentType.toLowerCase().contains("application/json");
                    boolean isApiCall = "xhr".equals(resourceType) || "fetch".equals(resourceType);

                    if (isSuccessful && isJson && isApiCall) {
                        log.debug("Processing response from: {}", url);
                        try {
                            String responseBody = response.text();
                            JsonNode jsonNode = objectMapper.readTree(responseBody);

                            // Only attempt to parse endpoints that expose an "events" array.
                            // This avoids noisy responses from unrelated APIs while still
                            // logging their presence for further manual inspection.
                            if (jsonNode.has("events")) {
                                List<BettingEvent> parsedEvents = parseEventsFromJson(jsonNode, matchId);
                                events.addAll(parsedEvents);
                                log.info("Parsed {} events from {}", parsedEvents.size(), url);
                            } else {
                                log.debug("Skipping JSON without events array from {}", url);
                            }
                        } catch (Exception e) {
                            // Avoid logging full stack traces for noisy responses
                            log.warn("Error parsing response from {}: {}", url, e.getMessage());
                        }
                    } else {
                        log.debug(
                                "Skipping response from {} (status: {}, content-type: {}, type: {})",
                                url, status, contentType, resourceType);
                    }
                });

                // Navigate to Betano and handle cookie banner if present
                log.info("Navigating to {}", matchUrl);
                page.navigate(matchUrl);

                String acceptButtonSelector = "button:has-text(\"Accept\")";
                try {
                    page.waitForSelector(acceptButtonSelector, new Page.WaitForSelectorOptions().setTimeout(5000));
                    page.click(acceptButtonSelector);
                    page.waitForSelector(acceptButtonSelector,
                            new Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED));
                    log.debug("Cookie banner accepted");
                } catch (TimeoutError e) {
                    log.debug("Cookie banner not found or already dismissed");
                }

                page.waitForLoadState(LoadState.NETWORKIDLE);

                // Add a delay to ensure all API requests are intercepted
                page.waitForTimeout(5000);

                log.info("Scraping completed successfully. Total events captured: {}", events.size());
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
     * Parses betting events from JSON response and optionally filters by matchId.
     * The parsing logic maps Betano's API fields (event/market/selection IDs,
     * names and odds) to the domain models.
     */
    List<BettingEvent> parseEventsFromJson(JsonNode jsonNode, String matchId) {
        List<BettingEvent> events = new ArrayList<>();

        try {
            if (jsonNode.has("events") && jsonNode.get("events").isArray()) {
                JsonNode eventsNode = jsonNode.get("events");

                for (JsonNode eventNode : eventsNode) {
                    // Use values inside the JSON to determine if this event matches the requested matchId
                    String eventId = eventNode.path("id").asText(null);
                    String urlSegment = eventNode.path("url").asText(null);

                    if (matchId != null && !matchId.isBlank()) {
                        boolean matchesId = eventId != null && matchId.equals(eventId);
                        boolean matchesUrl = urlSegment != null && urlSegment.contains(matchId);
                        if (!matchesId && !matchesUrl) {
                            // Skip events that do not correspond to the requested match
                            continue;
                        }
                    }

                    String matchName = eventNode.path("name").asText();
                    String startTimeStr = eventNode.path("startTime").asText();
                    LocalDateTime startTime = parseDateTime(startTimeStr);

                    List<BettingMarket> markets = new ArrayList<>();

                    if (eventNode.has("markets") && eventNode.get("markets").isArray()) {
                        JsonNode marketsNode = eventNode.get("markets");

                        for (JsonNode marketNode : marketsNode) {
                            String marketId = marketNode.path("id").asText();
                            String marketName = marketNode.path("name").asText();
                            List<BettingSelection> selections = new ArrayList<>();

                            if (marketNode.has("selections") && marketNode.get("selections").isArray()) {
                                JsonNode selectionsNode = marketNode.get("selections");

                                for (JsonNode selectionNode : selectionsNode) {
                                    String selectionId = selectionNode.path("id").asText();
                                    String selectionName = selectionNode.path("name").asText();
                                    double odds = selectionNode.path("odds").asDouble();

                                    selections.add(BettingSelection.builder()
                                            .selectionId(selectionId)
                                            .selectionName(selectionName)
                                            .odds(odds)
                                            .build());
                                }
                            }

                            markets.add(BettingMarket.builder()
                                    .marketId(marketId)
                                    .marketType(marketName)
                                    .selections(selections)
                                    .build());
                        }
                    }

                    events.add(BettingEvent.builder()
                            .eventId(eventId)
                            .matchName(matchName)
                            .startTime(startTime)
                            .markets(markets)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Error parsing JSON response: {}", e.getMessage(), e);
        }

        log.info("Parsed {} events from JSON response", events.size());
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

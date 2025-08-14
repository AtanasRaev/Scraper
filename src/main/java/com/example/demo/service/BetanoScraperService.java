package com.example.demo.service;

import com.example.demo.config.ProxyConfig;
import com.example.demo.model.BettingEvent;
import com.example.demo.model.BettingMarket;
import com.example.demo.model.BettingSelection;
import com.example.demo.util.UserAgentRotator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

        // Determine the final URL for the match. If matchId is a numeric value the
        // scraper first resolves the corresponding slug via Betano's known
        // `/api/events/{id}` endpoint. When matchId already contains a path or slug,
        // it is used as-is.
        String matchUrl = buildMatchUrl(matchId);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);

            try (BrowserContext context = createBrowserContext(browser)) {
                Page page = context.newPage();

                // Betano exposes odds data through a handful of API endpoints that
                // were identified via the browser's developer tools. Instead of
                // capturing every single request with a catch-all regex, the scraper
                // now watches only these specific paths. This keeps the logs focused
                // and avoids parsing irrelevant traffic.
                List<String> apiEndpoints = List.of("bettingoffer", "events", "live-data");
                AtomicBoolean apiEndpointHit = new AtomicBoolean(false);

                page.route("**/*", route -> {
                    String url = route.request().url();
                    if (apiEndpoints.stream().anyMatch(url::contains)) {
                        apiEndpointHit.set(true);
                        log.debug("Intercepted API request: {}", url);
                    }
                    route.resume();
                });

                // Set up response handling for intercepted requests
                page.onResponse(response -> {
                    String url = response.url();
                    int status = response.status();
                    String contentType = response.headers().get("content-type");
                    String resourceType = response.request().resourceType();

                    boolean matchesEndpoint = apiEndpoints.stream().anyMatch(url::contains);
                    boolean isSuccessful = status >= 200 && status < 300;
                    boolean isJson = contentType != null && contentType.toLowerCase().contains("application/json");
                    boolean isApiCall = "xhr".equals(resourceType) || "fetch".equals(resourceType);

                    if (matchesEndpoint && isSuccessful && isJson && isApiCall) {
                        apiEndpointHit.set(true);
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

                if (!apiEndpointHit.get()) {
                    log.warn("No betting API endpoints were called during navigation to {}", matchUrl);
                }

                log.info("Scraping completed successfully. Total events captured: {}", events.size());
            } catch (Exception e) {
                log.error("Error during scraping: {}", e.getMessage(), e);
            }
        }

        return events;
    }

    /**
     * Builds the final URL that Playwright should navigate to. If the provided
     * match identifier is numeric, Betano's event API is queried to resolve the
     * slug/path used for the web page. If the identifier already represents a
     * path or slug, it is returned unchanged.
     */
    String buildMatchUrl(String matchId) {
        String path = resolveMatchPath(matchId);

        String url = BETANO_URL;
        if (path != null && !path.isBlank()) {
            if (path.startsWith("http")) {
                url = path;
            } else {
                url = BETANO_URL + (path.startsWith("/") ? path : "/" + path);
            }
        }

        return url;
    }

    /**
     * Resolves a numeric match identifier to the corresponding slug by calling
     * Betano's `/api/events/{id}` endpoint. If the identifier already contains a
     * path/slug or the lookup fails, the original value is returned.
     */
    String resolveMatchPath(String matchId) {
        if (matchId == null || matchId.isBlank()) {
            return "";
        }

        // Only perform a lookup when the identifier is purely numeric
        if (matchId.matches("\\d+")) {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BETANO_URL + "/api/events/" + matchId))
                        .header("accept", "application/json")
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    JsonNode node = objectMapper.readTree(response.body());
                    String slug = node.path("slug").asText(null);
                    if (slug == null || slug.isBlank()) {
                        slug = node.path("url").asText(null);
                    }

                    if (slug != null && !slug.isBlank()) {
                        return slug.startsWith("/") ? slug.substring(1) : slug;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to resolve slug for match {}: {}", matchId, e.getMessage());
            }
        }

        // Either the matchId already contained a path or the lookup failed.
        return matchId;
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
            // Betano responses may return a top-level "events" array or a
            // "fixtures" array containing an "event" object for metadata.
            List<JsonNode> eventNodes = new ArrayList<>();

            if (jsonNode.has("events") && jsonNode.get("events").isArray()) {
                jsonNode.get("events").forEach(eventNodes::add);
            } else if (jsonNode.has("fixtures") && jsonNode.get("fixtures").isArray()) {
                for (JsonNode fixture : jsonNode.get("fixtures")) {
                    // Each fixture may nest the actual event under an "event" key
                    // while the markets remain at the fixture level.
                    JsonNode eventNode = fixture.has("event") ? fixture.get("event") : fixture;
                    // Attach markets node so downstream parsing can treat it uniformly
                    if (eventNode instanceof ObjectNode && !eventNode.has("markets") && fixture.has("markets")) {
                        ((ObjectNode) eventNode).set("markets", fixture.get("markets"));
                    }
                    eventNodes.add(eventNode);
                }
            }

            for (JsonNode eventNode : eventNodes) {
                String eventId = eventNode.path("id").asText(null);
                String urlSegment = eventNode.path("url").asText(null);

                if (matchId != null && !matchId.isBlank()) {
                    boolean matchesId = eventId != null && matchId.equals(eventId);
                    boolean matchesUrl = urlSegment != null && urlSegment.contains(matchId);
                    if (!matchesId && !matchesUrl) {
                        continue;
                    }
                }

                String matchName = eventNode.path("name").asText();
                String startTimeStr = eventNode.path("startTime").asText();
                LocalDateTime startTime = parseDateTime(startTimeStr);

                List<BettingMarket> markets = new ArrayList<>();
                JsonNode marketsNode = eventNode.path("markets");
                if (marketsNode.isArray()) {
                    for (JsonNode marketNode : marketsNode) {
                        String marketId = marketNode.path("id").asText();
                        String marketName = marketNode.path("name").asText();

                        List<BettingSelection> selections = new ArrayList<>();
                        JsonNode selectionsNode = marketNode.path("selections");
                        if (!selectionsNode.isArray()) {
                            selectionsNode = marketNode.path("outcomes");
                        }

                        if (selectionsNode.isArray()) {
                            for (JsonNode selectionNode : selectionsNode) {
                                String selectionId = selectionNode.path("id").asText();
                                String selectionName = selectionNode.path("name").asText();

                                double odds = 0.0;
                                if (selectionNode.has("odds")) {
                                    JsonNode oddsNode = selectionNode.get("odds");
                                    if (oddsNode.isNumber()) {
                                        odds = oddsNode.asDouble();
                                    } else if (oddsNode.has("decimal")) {
                                        odds = oddsNode.get("decimal").asDouble();
                                    }
                                } else if (selectionNode.has("price")) {
                                    JsonNode priceNode = selectionNode.get("price");
                                    if (priceNode.isNumber()) {
                                        odds = priceNode.asDouble();
                                    } else if (priceNode.has("decimal")) {
                                        odds = priceNode.get("decimal").asDouble();
                                    }
                                }

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

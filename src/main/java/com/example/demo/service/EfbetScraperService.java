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

/**
 * Service responsible for scraping odds from Efbet.
 *
 * The implementation mirrors {@link BetanoScraperService} but adjusts the
 * navigation URL, request interception pattern and JSON parsing logic to the
 * Efbet website. The parsing is intentionally conservative because the real
 * structure of Efbet's API responses may differ. The service is designed to be
 * extended once the exact schema is known.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EfbetScraperService {

    private final ProxyConfig proxyConfig;
    private final ObjectMapper objectMapper;
    private final UserAgentRotator userAgentRotator;

    private static final String EFBET_URL = "https://www.efbet.com/bg/sports";
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 5000;

    /** Flag indicating whether we should retry without proxy. */
    private boolean tryWithoutProxy = false;

    /**
     * Scrapes betting data from Efbet.
     *
     * @return list of betting events with markets and selections
     */
    public List<BettingEvent> scrapeBettingData() {
        log.info("Starting Efbet scraping process");
        List<BettingEvent> events = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);

            try (BrowserContext context = createBrowserContext(browser)) {
                Page page = context.newPage();

                // Intercept API requests that likely carry odds information
                page.route(Pattern.compile(".*offer.*"), route -> {
                    log.debug("Intercepted API request: {}", route.request().url());
                    route.resume();
                });

                page.onResponse(response -> {
                    String url = response.url();
                    if (url.contains("offer")) {
                        try {
                            log.debug("Processing response from: {}", url);
                            String body = response.text();
                            JsonNode json = objectMapper.readTree(body);
                            List<BettingEvent> parsed = parseEventsFromJson(json);
                            events.addAll(parsed);
                        } catch (Exception e) {
                            log.error("Error processing response: {}", e.getMessage(), e);
                        }
                    }
                });

                // Navigate to Efbet and wait for network to be idle
                log.info("Navigating to Efbet");
                page.navigate(EFBET_URL);
                page.waitForLoadState(LoadState.NETWORKIDLE);
                page.waitForTimeout(5000); // ensure all requests captured

                log.info("Efbet scraping finished");
            } catch (Exception e) {
                log.error("Error during Efbet scraping: {}", e.getMessage(), e);
            }
        }

        return events;
    }

    /**
     * Launches the browser with optional proxy and retry logic identical to
     * BetanoScraperService.
     */
    private Browser launchBrowser(Playwright playwright) {
        int retries = 0;
        tryWithoutProxy = false;

        while (retries < MAX_RETRIES) {
            try {
                BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setSlowMo(100);

                String proxyUrl = proxyConfig.getProxyUrl();
                if (proxyUrl != null && !tryWithoutProxy) {
                    log.info("Setting global proxy at browser launch: {}", proxyUrl);
                    launchOptions.setProxy(new Proxy(proxyUrl));
                } else if (tryWithoutProxy) {
                    log.info("Trying to launch browser without proxy after previous failures");
                }

                log.info("Launching headless browser for Efbet scraping");
                return playwright.chromium().launch(launchOptions);
            } catch (Exception e) {
                retries++;
                log.warn("Failed to launch browser (attempt {}/{}): {}", retries, MAX_RETRIES, e.getMessage());

                if (retries == 1 && proxyConfig.getProxyUrl() != null && !tryWithoutProxy) {
                    tryWithoutProxy = true;
                    log.info("Proxy connection failed. Will try without proxy on next attempt.");
                }

                if (retries >= MAX_RETRIES) {
                    log.error("Max retries reached. Unable to launch browser for Efbet.", e);
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
     * Creates a browser context with proxy configuration if enabled and a
     * random user agent for stealth.
     */
    private BrowserContext createBrowserContext(Browser browser) {
        String userAgent = userAgentRotator.getRandomUserAgent();
        log.info("Using user agent: {}", userAgent);

        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setUserAgent(userAgent)
                .setViewportSize(1920, 1080)
                .setIgnoreHTTPSErrors(true);

        String proxyUrl = proxyConfig.getProxyUrl();
        if (proxyUrl != null && !tryWithoutProxy) {
            log.info("Using proxy in browser context: {}", proxyUrl);
            options.setProxy(new Proxy(proxyUrl));
        } else if (tryWithoutProxy) {
            log.info("Creating browser context without proxy (fallback mode)");
        } else {
            log.info("No proxy configured");
        }

        return browser.newContext(options);
    }

    /**
     * Parses betting events from Efbet's API response. This is a best-effort
     * implementation – the actual JSON shape might differ and should be
     * adjusted when integrating with the real API.
     */
    private List<BettingEvent> parseEventsFromJson(JsonNode jsonNode) {
        List<BettingEvent> events = new ArrayList<>();

        try {
            if (jsonNode.has("events") && jsonNode.get("events").isArray()) {
                JsonNode eventsNode = jsonNode.get("events");

                for (JsonNode eventNode : eventsNode) {
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
            log.error("Error parsing Efbet JSON response: {}", e.getMessage(), e);
        }

        return events;
    }

    /**
     * Parses a date-time string to {@link LocalDateTime}. Adjust the formatter
     * based on Efbet's actual date representation.
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e) {
            log.warn("Failed to parse date-time: {}", dateTimeStr);
            return LocalDateTime.now();
        }
    }
}


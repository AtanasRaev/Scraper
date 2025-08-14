# Betano.bg Odds Scraper

A stealthy and fast scraper that collects odds from [Betano.bg](https://www.betano.bg) for use in an arbitrage betting system.

## Features

- Headless browser automation using Playwright
- Proxy rotation support to avoid detection
- User agent rotation for enhanced stealth
- XHR/JSON API request interception for efficient data extraction
- Scheduled scraping at configurable intervals
- JSON output with structured betting data
- Error handling and retry logic
- Logging with timestamps

## Requirements

- Java 21 or higher
- Gradle

## Configuration

The application can be configured through the `application.properties` file:

```properties
# Proxy configuration
proxy.enabled=false
proxy.server=localhost
proxy.port=8080
proxy.username=
proxy.password=

# Scraper configuration
scraper.interval=45000
scraper.output.enabled=true
scraper.output.directory=./scraper-output
```

### Proxy Configuration

- `proxy.enabled`: Set to `true` to enable proxy usage
- `proxy.server`: Proxy server hostname or IP
- `proxy.port`: Proxy server port
- `proxy.username`: Username for proxy authentication (optional)
- `proxy.password`: Password for proxy authentication (optional)

### Scraper Configuration

- `scraper.interval`: Scraping interval in milliseconds (default: 45000 ms = 45 seconds)
- `scraper.output.enabled`: Set to `true` to save scraping results to JSON files
- `scraper.output.directory`: Directory where JSON files will be saved

## Usage

### Running the Application

```bash
./gradlew bootRun
```

### API Endpoints

- `GET /api/scraper/betano`: Triggers the scraping process and returns the results as JSON

## Output Format

The scraper outputs betting data in the following JSON format:

```json
[
  {
    "matchName": "Ludogorets vs CSKA Sofia",
    "startTime": "2023-05-15T19:30:00",
    "markets": [
      {
        "marketType": "1X2",
        "selections": [
          {
            "selectionName": "Home",
            "odds": 1.75
          },
          {
            "selectionName": "Draw",
            "odds": 3.5
          },
          {
            "selectionName": "Away",
            "odds": 4.2
          }
        ]
      }
    ]
  }
]
```

## Implementation Details

### Components

- **BetanoScraperService**: Core service that handles browser automation and data extraction
- **BetanoScraperController**: REST controller that exposes the scraper functionality
- **BetanoScraperScheduler**: Scheduler that runs the scraper at regular intervals
- **ProxyConfig**: Configuration for proxy settings
- **UserAgentRotator**: Utility for rotating user agents to avoid detection

### Stealth Techniques

- Headless browser operation
- Random user agent rotation
- Proxy support
- Request throttling
- Error handling and retries

## Customization

To adapt the scraper for different markets or betting sites:

1. Modify the `BETANO_URL` constant in `BetanoScraperService`
2. Update the request interception patterns to match the target site's API endpoints
3. Adjust the JSON parsing logic in `parseEventsFromJson()` to match the structure of the target site's API responses
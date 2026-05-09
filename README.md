# AI Summary

Daily AI changes summarizer — fetches trending AI repositories from GitHub, generates a concise summary using DeepSeek, and posts it as a thread on X (Twitter).

## Architecture

```
┌─────────────┐     ┌──────────────────┐     ┌───────────────┐
│ GitHub API   │────▶│ DeepSeek (via    │────▶│ X/Twitter API │
│ (trending    │     │ Spring AI)       │     │ (OAuth 1.0a)  │
│  AI repos)   │     │     summarizes   │     │  post thread  │
└─────────────┘     └──────────────────┘     └───────────────┘
       ▲                                             ▲
       │         Daily Scheduler (9 AM)              │
       └──────────────────┬──────────────────────────┘
                          │
                   Spring Boot 3.4
```

## Stack

- **Spring Boot 3.4** — application framework
- **Spring AI** (OpenAI starter) — DeepSeek integration via OpenAI-compatible API
- **GitHub API** (kohsuke/github-api) — fetch trending AI repos
- **Twitter4J** — post thread to X/Twitter
- **Java 21**

## Setup

### 1. Prerequisites

- Java 21+
- Maven 3.9+

### 2. API Credentials

Copy `.env.example` to `.env` and fill in your credentials:

```bash
cp .env.example .env
```

**Required services:**

| Service | How to get credentials |
|---------|----------------------|
| DeepSeek | [platform.deepseek.com/api_keys](https://platform.deepseek.com/api_keys) |
| GitHub (optional) | [github.com/settings/tokens](https://github.com/settings/tokens) — needs `public_repo` scope |
| X/Twitter | [developer.x.com](https://developer.x.com/en/portal/projects) — create a project, get OAuth 1.0a keys |

For Twitter/X, you need a project with **Read and Write** permissions under "User authentication settings". Generate the Consumer Key/Secret, then generate Access Token/Secret.

### 3. Run

```bash
# Load env vars and run
source .env && mvn spring-boot:run

# Or export individually
export DEEPSEEK_API_KEY=sk-...
export TWITTER_CONSUMER_KEY=...
export TWITTER_CONSUMER_SECRET=...
export TWITTER_ACCESS_TOKEN=...
export TWITTER_ACCESS_TOKEN_SECRET=...
export DAILY_POST_ENABLED=true
mvn spring-boot:run
```

### 4. Schedule

By default, the job runs daily at 9:00 AM. Change the cron in `application.yml`:

```yaml
app:
  daily-post:
    enabled: true
    cron: "0 0 9 * * *"  # 9 AM daily
```

## Project Structure

```
src/main/java/com/aisummary/
├── AiSummaryApplication.java          # Entry point + @EnableScheduling
├── config/
│   ├── DeepSeekConfig.java             # Spring AI OpenAI client → DeepSeek
│   └── TwitterConfig.java             # Twitter OAuth credentials
├── model/
│   └── TrendingRepo.java              # TrendingRepo + DailySummary records
├── service/
│   ├── GitHubTrendingService.java     # Fetch AI repos from GitHub search
│   ├── DeepSeekSummaryService.java    # Summarize repos via DeepSeek
│   └── TwitterPostService.java        # Post thread to X/Twitter
└── scheduler/
    └── DailySummaryScheduler.java     # @Scheduled daily job
```

## License

MIT

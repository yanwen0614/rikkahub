# search Module

联网搜索 SDK：多 provider 搜索 + 抓取（scrape），供 app 助手工具与 MCP 共用。

## 服务矩阵（`SearchService.kt` 内 `SearchServiceOptions.TYPES`）

- Bing（默认，爬虫实现不稳定不推荐）/ RikkaHub / Tavily / Exa / Zhipu（智谱）/
  Doubao（豆包）/ SearXNG（自托管）/ LinkUp / Brave / Metaso（秘塔）/ Ollama /
  Perplexity / Firecrawl / Jina / Bocha（博查）/ Grok / Tinyfish / Serper / Custom JS。
- 新增服务：加 `*SearchService.kt` + 对应 `SearchServiceOptions` 子类，
  并在 `getService()` 注册。

## SearchService 接口

- `search(params, commonOptions, serviceOptions): Result<SearchResult>`；
  `scrape(params, commonOptions, serviceOptions): Result<ScrapedResult>`。
- `parameters()/scrapingParameters()` 描述工具参数（InputSchema）；
  `Description()` 为 Compose 设置页说明组件。
- 共享 OkHttp（`SearchService.init(client, ...)` 注入）与 KeyRoulette 实例。

## Key 池（`KeyPoolExecutor.kt`）

- `withKeyRetry(keys, providerId, cooldownMillis)`：LRU 轮询选 key，失败换 key 重试，
  单次最多 `KEY_POOL_MAX_ATTEMPTS=3` 个不同 key。
- 仅 401/402/403/429/432（Tavily 配额）视为 key 失败并冷却（`keyCooldownHoursToMillis`，
  最小 1h）；网络/参数错误直接返回；全部冷却则直接失败不降级。

## i18n

- 自有 `search/src/main/res/values*/strings.xml`（仅服务说明等少量 key，
  如 `bing_desc`/`searxng_desc_*`/`custom_js_desc`），其余复用 app 侧字符串。

# 全源搜索真实性审计（2026-10-25）

针对「看似可以搜索，但返回的全是固定的无关内容」这个问题，对仓库内 95 个已注册源做了统一检索审计，
修复了 6 个假搜索源，并把 1 个网站完全不支持搜索的源如实降级。本文记录方法、结论、修复与未验证项。

## 结论摘要

| 结论 | 数量 | 说明 |
| --- | ---: | --- |
| 搜索正常（垃圾查询为空、真实标题能命中） | 40 | 见文末清单 |
| 未声明搜索（本来就不提供搜索） | 4 | DDMANHUA、GUFENGMANHUA、HENTAICOSPLAY、LIGHTNOVEL_WIKI |
| 站点侧兜底（解析器无法区分真假结果） | 1 | TUKU |
| 待复核（客户端过滤，请求里检测不到关键字） | 1 | HITOMILA |
| 本次修复的假搜索 | 6 | GODA、MH1234、PORN3DX、PORN3DX_IMAGE、ROYALROAD、LANRARAGI |
| 因网络/反爬未能验证 | 49 | 26 连接超时、13 TLS 重置、3 次 403、2 次 CloudFlare 挑战、1 审计超时、4 其它 |

## 审计方法

审计实现为 `AllSourcesSearchAuditTest`（`SEARCH_AUDIT=1` 显式启用，普通 `./gradlew test` 离线跳过）。
每个源走四步：

1. **浏览**：空过滤器取列表，作为基准 URL 集合。
2. **垃圾查询 A / B**：两个互不相同、不可能存在的关键字（如 `a5fGdqZ9`、`qzxvbnmkjhgfdsprt`）。
3. **真实查询**：取浏览首项的标题（完整标题、截断版本、首个单词逐个尝试），命中即停。

证据来自两层：

- **结果层**：每步返回的 URL 集合，用于比较「A=B」「A=浏览」。
- **请求层**：`MangaLoaderContext` 的拦截器记录每步真实发出的 URL（带 `@source` 标签），
  用于判断关键字是否真的被发送——这能区分「网站忽略参数」和「解析器根本没带参数」。

判定规则：

| 判定 | 条件 |
| --- | --- |
| `FAKE_BROWSE` | 两次垃圾查询结果相同且等于浏览列表 |
| `FAKE_FIXED` | 两次垃圾查询结果相同但不等于浏览列表 |
| `FAKE_NOT_SENT` | 结果固定且请求中不含关键字 |
| `SUSPICIOUS_VARYING` | 结果随查询变化，但垃圾查询也有结果 |
| `OK` | 垃圾查询均为空且真实标题能命中 |
| `ERROR` / `TIMEOUT` | 浏览或搜索请求失败 / 审计超时 |

## 已修复的假搜索

| 源 | 症状 | 根因 | 修复 |
| --- | --- | --- | --- |
| GODA | 任意关键字都返回同一批作品 | 无匹配时站点仍返回 200，正文是「暫無漫畫」空态 + 「您可能喜歡」推荐卡片，解析器把推荐卡当结果 | 识别空态标题，命中直接返回空列表 |
| MH1234 | 任意关键字都返回同样的 6 部推荐 | 无匹配时结果区为空，卡片只出现在「推荐漫画」区块 `section.comic-section` | 搜索解析前移除推荐区块 |
| PORN3DX / PORN3DX_IMAGE | 返回首页画廊 | 首页的 `searchQuery` 参数被站点忽略，真实搜索在独立路由 | 有查询时改用 `/search?q=` |
| ROYALROAD | 返回与浏览相同的列表 | 站点搜索表单使用 `title` 参数，`q` 被忽略 | 改用 `title=` |
| LANRARAGI | 搜索无意义（永远返回全量存档的前 N 条） | 之前完全没发搜索请求，只在本地切分全量列表 | 改用 `/api/search?filter=` 服务端检索 |
| LIGHTNOVEL_WIKI | 任意关键字都返回全量 72 本目录（无匹配时正文仍是全量，仅状态码 404） | `/books` 忽略所有 `q[...]` 查询参数，站点没有可用的搜索 | 如实设置 `isSearchSupported = false`，并在非 2xx 时返回空 |

修复后的复测（同一审计脚本）：

| 源 | 修复前 | 修复后 |
| --- | --- | --- |
| GODA | 垃圾查询返回 14 条推荐 | 垃圾查询 0 条，真实查询「元尊」30 条 |
| MH1234 | 垃圾查询返回 6 条推荐 | 垃圾查询 0 条，真实查询「影侍」1 条 |
| PORN3DX / PORN3DX_IMAGE | 垃圾查询返回 10 条首页内容 | 垃圾查询 0 条，真实查询 30 条 |
| ROYALROAD | 垃圾查询返回 20 条浏览列表 | 垃圾查询 0 条，真实查询「RUSH」20 条 |
| LANRARAGI | 垃圾查询返回 10 条存档 | 垃圾查询 0 条，真实查询 1 条 |

## 站点侧问题（解析器已尽力，仍需跟进）

- **TUKU**：`/list/<关键字>` 对任何关键字都返回整库列表，页面标题写死「共有10000个结果」，
  垃圾查询稳定返回 21 条、真实查询也能命中。解析器无法从页面区分「真结果」和「兜底全库」，
  只能在站点修好该接口后复测。
- **MHKAMI**：`m.mhkami.com/search?searchkey=...` 307 跳转到 `baidu.com`，搜索链路已断，
  需要确认站点是否更换域名或加了反爬。
- **BAIHEHUI（百合会）**：搜索 302 跳到 `/user/login`，未登录拿不到结果；这是账号权限限制，
  不绕过。
- **BILINOVEL**：`search.html?searchkey=` 对真实标题返回空响应体（HTTP 200，0 字节），
  与哔哩轻小说移动站的既有反爬一致，需要浏览器态 Cookie 才能搜索。

## 待复核清单（判定 OK，但真实标题 0 命中）

这 13 个源垃圾查询都返回 0 条（未被判为假搜索），但用自家列表标题复搜没有命中，原因分三类：

| 源 | 域名 | 浏览条数 | 复搜关键字 | 原因 |
| --- | --- | ---: | --- | --- |
| BILINOVEL | www.bilinovel.com | 30 | 探索者 | 搜索接口返回空响应体 |
| BAIHEHUI | www.yamibo.com | 50 | 刘小姐 | 搜索跳转登录页 |
| MHKAMI | m.mhkami.com | 48 | 心动频率~ | 搜索 307 跳 baidu |
| ANIMESHENTAI | animeshentai.biz | 20 | NTR Kish | 返回 6 条但无目标，命名/别名差异待查 |
| DMBUS | dmbus.cc | 36 | 借命灯 | 搜索需要精确标题，待查 |
| FANTUAN | acgfta.com | 24 | 战车椅子 | 搜索路径与站点模板不一致，待查 |
| UZVOD | uzvod.com | 24 | 朱色的面具 | 播放类站点按英文名/别名索引，待查 |
| ANIWORLD | aniworld.to | 108 | （未取到） | 首项标题不含可检索片段 |
| FANQIE | www.fqdm.cc | 0 | — | 浏览列表本身为空，站点结构可能已变 |
| JIBI | www.jibi.cc | 0 | — | 浏览列表本身为空 |
| MEMO | memojav.com | 0 | — | 浏览列表本身为空 |
| NOVELCOOL | www.novelcool.com | 0 | — | 浏览列表本身为空 |
| NOVELFULL | novelfull.net | 0 | — | 浏览列表本身为空 |

## 未能验证的 49 个源

审计期间的失败分布（49 个源）：26 次连接超时、13 次 TLS 连接被重置、3 次 403、2 次 CloudFlare 挑战、
1 次整体审计超时（`WNACG`）、1 次证书链错误、1 次需浏览器完成验证码（`BAOZIMH`）、1 次 404（`FEIFEIMANHUA`）、
1 次不支持的操作、1 次连接拒绝。
这些是站点侧反爬或本机网络不可达，与解析器逻辑无关，需要在可访问网络下重跑。
逐源明细见 `build/reports/search-audit/search-audit.md`。

## 验证

```bash
# 静态编译
./gradlew compileKotlin --no-daemon

# 假搜索回归测试（离线夹具，5 个用例）
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.FakeSearchGuardTest" --no-daemon

# 全源在线审计（默认跳过，需显式开启）
SEARCH_AUDIT=1 SEARCH_AUDIT_CONCURRENCY=8 SEARCH_AUDIT_TIMEOUT_SEC=150 \
  ./gradlew cleanTest test \
  --tests "org.skepsun.kototoro.parsers.site.all.AllSourcesSearchAuditTest" --no-daemon
```

- `FakeSearchGuardTest`：5/5 通过。夹具位于 `src/test/resources/fixtures/search/`，
  覆盖 GODA 与 MH1234 的空态/正常结果结构，以及 LIGHTNOVEL_WIKI 不声明搜索的约束。
- 全量 `./gradlew test`：`BUILD FAILED`，32 个用例失败，全部是联网用例——
  `ContentParserTest`（只参数化 WNACG、COPYMANGA 两个源）、`BuonduaTest`、`CopyContentAuthTest`、
  `CopyContentImageLoadTest`、`CopyContentXigelideTest`、`WnacgReadTest`、`WnacgReadFlowTest`，
  失败原因为 `SocketTimeoutException` / `UncompletedCoroutinesError`（站点不可达）。
  本次改动的 6 个解析器都没有失败的用例。
- 审计产物：`build/reports/search-audit/search-audit.tsv`（逐源全部指标）、
  `search-audit.md`（可读报告）、`progress.tsv`（断点进度，审计中断后可续跑）。

## 后续建议

1. 在能连通的网络下重跑审计，把 49 个未验证源补齐；`progress.tsv` 支持续跑。
2. 站点侧兜底（TUKU）与需登录/需浏览器的源（BAIHEHUI、BILINOVEL、MHKAMI）按源单独修复，
   不要用客户端启发式去「猜」结果的真实性。
3. 新增源时把「垃圾查询返回空」纳入集成测试，避免再次出现忽略关键词却声明支持搜索的解析器。

# 免费电子书源调研（出版物类：文学 / 哲学 / 数学）

> 调研日期：2026-09（7890 代理实测）
> 目的：为解析器选材——寻找**资源全面**、**偏出版物**（文学、哲学、数学）的免费电子书源，评估列表/搜索、详情、下载三条链路是否可被解析。不关注版权（仅作站点结构研究）。

## 结论速览

| 站点 | 实测状态 | 内容定位 | 评估 |
|---|---|---|---|
| **Library Genesis**（libgen.li / .la / .vg 三镜像） | ✅ 全链路 | 900 万+ 学术/出版物，文学哲学数学极全 | **已实现解析器** `LibraryGenesis.kt`；.la/.vg 同协议 |
| **Internet Archive**（archive.org） | ✅ API 全链路 | 4230 万+ 项目，含海量扫描出版物，开放借阅+公有领域下载 | **已实现解析器** `InternetArchive.kt`；`advancedsearch.php` JSON + `metadata` + 直链下载 |
| **Standard Ebooks**（standardebooks.org） | ✅ 列表+详情+下载 | 精选公有领域文献逐字校对版，文学为主，质量极高 | **已实现解析器** `StandardEbooks.kt`；epub/azw3/kepub 直链 |
| **Project Gutenberg**（gutenberg.org） | ✅ OPDS 全链路 | 7 万+ 公有领域书（文学、哲学） | **已实现解析器** |
| **Open Library**（openlibrary.org） | ⚠️ API 可用，下载跳 archive | archive 的目录前端，借阅书需登录 | 可作为 archive 补充；`search.json` 好使 |
| **LibreTexts**（libretexts.org） | ⚠️ 主站 200，子域 405 Human Verification | 数学/科学教科书（CC 协议） | 部分受阻，`math.` 子域爬不动 |
| **OpenStax**（openstax.org） | ⚠️ 200 但 React SPA | 大学数学/物理教科书（CC） | 静态解析困难，下载链接 JS 动态生成 |
| **书格**（shuge.org） | ✅ 200 | 中文古籍/珍本高清扫描 | 收藏向，非通用电子书格式，优先级低 |
| **维基文库**（zh.wikisource.org） | ✅ 200 | 公有领域中文古籍全文（MediaWiki） | 可走 MediaWiki API，文学/哲学丰富 |

## 受阻 / 不可用

| 站点 | 现象 |
|---|---|
| Anna's Archive（annas-archive.se/.li） | 首页 200，搜索页为 JS 反爬验证壳（空内容）；.se 超时 |
| Z-Library（z-lib.io / z-lib.gs / 1lib.to / 1lib.vn） | 域名失效或超时；z-library.se 502；先前 .sk 走 DiamWall 拦 |
| libgen 镜像 .rs/.is/.st/.gs/.ee | 经本代理全部超时（仅 .li/.la/.vg 可用） |
| 鸠摩搜索（jiumodiary.com） | 需微信扫码验证码，网页端不可自动化 |
| ManyBooks / FeedBooks / OceanofPDF / ePDF / dokumen.pub | Cloudflare "Just a moment" 人机验证 |
| ebook-hunter.org | 502 |
| welib.net | 200，但为中文网文站，非出版物，跳过 |

## 关键协议细节（解析器落地方向）

### Internet Archive（最高价值）
- 搜索：`GET https://archive.org/advancedsearch.php?q=<query>&fl[]=identifier,title,year&rows=N&output=json`
  - 实测 `title:(philosophy of mathematics)` → numFound 259
- 元数据：`GET https://archive.org/metadata/<identifier>` → 含 files[]，可筛 `.pdf/.epub/.djvu/.txt`
- 下载：`GET https://archive.org/download/<identifier>/<file>`（直链）
- 注意：`/search?query=` HTML 页是 JS 渲染壳，解析器应走 API 而非 HTML

### Standard Ebooks
- 列表：`https://standardebooks.org/ebooks`（可加 `?type=fiction` 等过滤）
- 详情：`https://standardebooks.org/ebooks/<author>/<title>/<translator>` 200 可解析
- 下载：`/ebooks/.../downloads/<slug>_advanced.epub`、`.azw3`、`.kepub.epub`、`.epub` 直链
- 全部公有领域，无 DRM，无登录

### Open Library
- 搜索：`https://openlibrary.org/search.json?q=...&fields=key,title,author_name,ia,public_scan_b`
  - `public_scan_b=true` 表示公有领域可下载；`ia` 字段给出 archive 条目
- 详情：`https://openlibrary.org/works/<OLxxx>.json` / `books/<OLxxx>.json`
- 下载：可下载书实为 archive.org 条目，最终落到 archive 下载；借阅书需登录 → 对解析器而言不如直接做 archive.org

### LibGen 镜像（.la/.vg 与 .li 同协议，已实现）
- 搜索：`index.php?req=<q>&res=25&view=simple&phrase=1&column=def`
- 详情：`ads.php?md5=<md5>`
- 下载：`get.php?md5=<md5>&key=<一次性key>` → 302 到 cdn*.booksdl.lc

## 建议

1. **优先实现 Internet Archive 解析器**（`PagedMangaParser` 不适用，需按 API 分页；数据结构完全 JSON，离线夹具易做）——覆盖面最广、含哲学/数学扫描本、可复用 gutenberg-style 分类。
2. **其次实现 Standard Ebooks 解析器**——文学向高质量、下载最干净，列表+详情 HTML 结构稳定。
3. 中文出版物如需补充，可评估维基文库（MediaWiki API，稳定）；书格仅作古籍收藏。
4. Z-Library / Anna's Archive 反爬过重（DiamWall / JS 验证墙），不投入。

## 验证命令

```bash
PX="http://127.0.0.1:7890"; UA="Mozilla/5.0 ... Chrome/120.0"
# archive
curl -s -x $PX -A "$UA" "https://archive.org/advancedsearch.php?q=title:(philosophy+of+mathematics)&fl[]=identifier,title&rows=3&output=json"
curl -s -x $PX -A "$UA" "https://archive.org/metadata/foundationsmath00carugoog"
# standardebooks
curl -s -x $PX -A "$UA" "https://standardebooks.org/ebooks"
curl -s -x $PX -A "$UA" "https://standardebooks.org/ebooks/thomas-mann/short-fiction/various-translators"
# openlibrary
curl -s -x $PX -A "$UA" "https://openlibrary.org/search.json?q=philosophy+of+mathematics&limit=3"
```

## 实现记录（2026-11-12）

两个「强烈推荐」源已落地为解析器，`site/all`，`ContentType.NOVEL`，域名走 `configKeyDomain`，KSP 自动生成 `ContentParserSource.INTERNETARCHIVE` / `STANDARDEBOOKS`（`.github/summary.yaml` total 118 → 120）。

### InternetArchive.kt（archive.org）

| 环节 | 请求 | 解析要点 |
|---|---|---|
| 列表/搜索 | `GET /advancedsearch.php?q=…&fl[]=…&rows=50&page=N[&sort[]=…]&output=json` | `response.docs[]`；字段值可能是字符串**也可能是数组**（`creator`/`subject`/`collection` 常见数组，`language` 两种都有），统一按「字符串或数组」取值 |
| 详情 | `GET /metadata/{identifier}` | `metadata` 给标题/作者/年代/出版者/主题/版权状态；`files[]` 给可下载文件 |
| 下载 | `GET /download/{identifier}/{name}` | 直链、无登录；文件名按路径段编码（空格 → `%20`） |
| 封面 | `GET /services/img/{identifier}` | 200 `image/jpeg`，可直接当封面 |

- 默认查询带 `NOT collection:inlibrary`：借阅条目大多**没有**可下载文件，主动选「可借阅」标签才放开；详情页在没有可下载文件时给出说明。
- `files[]` 里 `EPUB` / `Text PDF` / `Additional Text PDF` / `Image Container PDF` / `DjVuTXT` 才是成品书，`_hocr`、`_abbyy.gz`、`_jp2.zip`、`scandata.zip`、`__ia_thumb.jpg` 等是中间产物，按白名单过滤并按格式去重（同一本书常有两个 PDF 版本）；`private == "true"` 的文件一律跳过。
- 排序口径：`POPULARITY → downloads desc`、`NEWEST → date desc`、`NEWEST_ASC → date asc`、`ALPHABETICAL → titleSorter asc`；有查询词的 `RELEVANCE` 不传 sort（交给站点相关度），**空关键词浏览**改按 `date desc`——全库默认序会把大量用户上传的噪音条目排在最前。
- 标签：语言（`language:(chi OR eng)`）、馆藏（`collection:gutenberg|opensource|inlibrary`），多语言之间是 OR、其余条件之间是 AND。
- 站内 `/search?query=` 是 JS 壳，**不要**去爬；只走 `advancedsearch.php`。

### StandardEbooks.kt（standardebooks.org）

| 环节 | 请求 | 解析要点 |
|---|---|---|
| 列表/搜索 | `GET /ebooks?page=N&per-page=24[&query=…][&sort=…][&tags[]=…]` | `ol.ebooks-list li[about="/ebooks/{author}/{title}[/{translator}]"]`；书名取 `p:not(.author) a span[property="schema:name"]`（缩略图锚点也在 `<p>` 外，不受影响） |
| 详情 | `GET /ebooks/{author}/{title}` | `article.ebook` 内的 schema.org 微数据：`h1[schema:name]`、`meta[schema:abstract]`、`div[schema:description]`、`aside#reading-ease`（字数/难度/系列）、`ul.tags` |
| 下载 | `/ebooks/.../downloads/{slug}.epub`、`.azw3`、`.kepub.epub`、`_advanced.epub` | 只取 `section#download` 里的 `a[property="schema:contentUrl"]`——`section#read-online` 里也有 contentUrl，但那是在线阅读页 |
| 更新时间 | `section#history time[datetime]` | 带偏移量的 ISO 8601（`+00:00`），`Instant.parse` 只吃 `Z` 结尾，改用 `OffsetDateTime.parse` |

- 排序口径：`default` 在站点侧就是「按发布/更新日期，新→旧」，映射到 `NEWEST`；`ALPHABETICAL → author-alpha`、`POPULARITY → popularity`（站点另有 `length` / `reading-ease`，本仓库 `SortOrder` 无对应枚举，不暴露）。
- 标签取站点筛选面板里的 19 个（adventure…travel），排除 `all`（等价于不加筛选）。

### 验证

```bash
# 离线夹具测试（默认 test 不联网）
./gradlew test --no-daemon \
  --tests "org.skepsun.kototoro.parsers.site.all.InternetArchiveTest" \
  --tests "org.skepsun.kototoro.parsers.site.all.StandardEbooksTest"
# → 14/14 通过（IA 7 个：列表/详情/chapters/私有与垃圾文件过滤/格式标记/查询构造/URL 构造；
#           SE 7 个：列表/空列表/详情/下载格式/格式标记/404/URL 构造）

# 在线端到端（需要出网代理；本机用 7890）
HTTPS_PROXY=http://127.0.0.1:7890 \
EBOOKS_INTEGRATION_TEST=1 INTERNETARCHIVE_INTEGRATION_TEST=1 STANDARDEBOOKS_INTEGRATION_TEST=1 \
./gradlew test --no-daemon --tests "org.skepsun.kototoro.parsers.site.all.EbooksIntegrationTest"
# → [IA] ok: … -> PDF (14.3 MB) https://archive.org/download/…/….pdf
# → [SE] ok: Sir Nigel, formats=[EPUB, AZW3, KEPUB, EPUB (Advanced)]
```

`./gradlew test --tests "org.skepsun.kototoro.parsers.site.all.*"` → 67 用例、6 失败、7 跳过；失败全在 `BuonduaTest`（`Failed to connect to buondua.com`，该测试类本身就是联网用例，与本次改动无关），新增两个测试类全绿。

# 黄果短剧（huangguoai.com）接入调研：能否作为新的视频解析器（2026-11-04）

结论：**可行，且成本偏低**。站点有公开 JSON API（列表 / 分类 / 详情 / 分集播放 / 搜索 / 榜单），
剧集按「影片 = 作品、每集 = 章节」组织，播放地址是标准 HLS（m3u8 + AES-128），
与仓库现有 `HENTAI_VIDEO` 类视频解析器（Jable / KanAV / FpoXxx / PlayVids / Tnaflix）的契约一一对应。

唯一的额外工作量是**封面是 AES 加密的二进制**，需要一个类似 `BaipiaoguaiImageDecoder` 的响应解密拦截器。

参考实现：`Minireel/HGhub`（第三方 Tauri 客户端）的 `docs/API.md` 与 `src-tauri/src/net.rs`。
本文档的接口结论**全部经本机实测复核**，未采信文档单方说法（与文档不一致之处已在 §7 标注）。

## 结论速览

| 项 | 实测结果 | 影响 |
|---|---|---|
| API 是否需要登录 | 列表 / 分类 / 详情 / 播放 / 搜索全部匿名可用 | ✅ 无需账号（符合 AGENTS.md 不绕权限） |
| 反爬 | 未见 Cloudflare / 签名 / 风控；连续 11 次请求全 200 | ✅ 无需 `CloudflareHelper` |
| 播放 | `play` 接口下发 m3u8，播放列表与分片实测 200 | ✅ 走现有 HLS 播放链路 |
| 封面 | AES-128-CBC 密文，key/iv 硬编码在前端 | ⚠️ 需解密拦截器 |
| 分页 | `page` / `size` 真分页，**`page` 从 1 起**（传 `page=0` 返回 400） | ✅ `PagedContentParser`（`firstPage = 1`） |
| 搜索 | `/api/search?q=` 可用（`女王` → 40 条 / 14 页） | ✅ 真搜索，非假搜索 |
| 排序 | `sort=new` 生效；未知值静默回落默认（热榜序） | ✅ 仅暴露 2 种 |
| 内容类型 | 全部为成人向 AI/真人竖屏短剧 | ⚠️ `ContentType.HENTAI_VIDEO` |

## 一、域名与请求约定

| 项 | 值 |
|---|---|
| 主域名 | `https://huangguoai.com`（`configKeyDomain`） |
| 备用 | `https://huangguoai.pages.dev/`（地址发布页，非 API 镜像） |
| 图片 CDN | `pic.qnlycc.cn`（2026-11-20 由 `pic.drleao.cn` 迁移而来；镜像曾见 `new.drleao.cn` / `new2.drleao.cn` / `image.drleao.cn`） |
| 视频 CDN | `yd-lphu-hls.tjyyjk.cn`（2026-11-20 起）；分片 `tp1..tp8.qnlycc.cn` |
| 必要头 | `User-Agent`（Chrome 桌面）、`Accept: application/json`、`Referer: https://huangguoai.com/` |

响应统一信封：`{"data": …, "status": 1, "msg": "", "req_time": …}`，`status == 1` 为成功。
`/api/**` 另有一个 `POST /api/edge/request` 边缘信封（`{method, path, body(base64), content_type}`），
**本站解析器不需要它**：公开 GET 直连即可，信封只为带 Bearer 的登录态接口服务。

## 二、可用的读取接口（均已实测）

| 用途 | 请求 | 说明 |
|---|---|---|
| 全站流 | `GET /api/videos?page=&size=[&sort=new]` | `page` **从 1 起**；默认序≈按 `hot` 倒序；`sort=new` 为最新 |
| 分类流 | `GET /api/videos/category/{cate}?page=&size=[&sort=new]` | cate ∈ `ai-duanju` / `ai-manju`（`ai-huanlian` / `ai-mogai` 于 2026-11-20 返回 410，已从源里移除） |
| 搜索 | `GET /api/search?q={kw}&page=&size=` | 参数名是 `q`（传 `keyword` 返回 `status:0`） |
| 详情 | `GET /api/videos/{id}` | 含 `episodes[]`、`cover`、`tags`、`score`、`is_finished` |
| 分集播放 | `POST /api/videos/{id}/episodes/{ep}/playback` | 返回 `data.streams.main` 即 HLS m3u8（2026-11-20 改版；老 `GET .../play?ep=` 的 `video_url` 已恒为空） |
| 榜单 | `GET /api/ranks/{hot\|recommend\|potential}` | 固定 20 条、忽略分页（不够做翻页流） |

列表响应：`{items: [VideoCard], pagination: {page, pages, size, total}}`。
`VideoCard` 关键字段：`id`、`title`、`cover`、`description`、`tags[]`、`score`(0–10)、
`episode_count`、`duration`(秒,字符串)、`hot`、`is_finished`、`is_original`、`author{id,name,avatar}`。

分类实测条目数（2026-11-04）：`ai-duanju` 1425，`ai-manju` / `ai-huanlian` / `ai-mogai` 各为独立子集，
全站 `total` 3218。

## 三、封面：AES-128-CBC 密文（实测可解）

图片 CDN 返回 `Content-Type: binary/octet-stream`，正文是密文。

```
算法: AES-128-CBC / NoPadding            (PKCS#7 自行去尾)
Key : f5d965df75336270                   (ASCII, 16B)
IV  : 97b60394abc2fbe1                   (ASCII, 16B)
```

实测解密结果：

| 样本 | 密文首字节 | 解出魔数 | 结果 |
|---|---|---|---|
| `...2026092411162272798.jpg` | `093de3b1` | `FFD8FFE0` | JPEG 1086×1448，174 813 B |
| `...2026091117244785674.png` | `dde28c47` | `89504E470D0A1A0A` | PNG，2 373 245 B |

两个补充事实（对实现很关键）：

1. **未加密的老图必须先探测魔数**：命中图片魔数则直接透传，不要解密。
2. **`auth_key` 未强制校验**：去掉查询串、甚至填伪造 `auth_key=1-0-0-deadbeef`，均返回 200 与同一份字节。
   因此图片 URL 可裁剪查询串以获得**长期有效**的封面地址（避免入库后过期）。

## 四、播放链路（实测可播）

```
POST /api/videos/12/episodes/1/playback   (body: {}, Content-Type: application/json)
→ data.streams.main = https://yd-lphu-hls.tjyyjk.cn/videos5/{hash}/{hash}.m3u8?auth_key=…&v=3
   #EXT-X-KEY:METHOD=AES-128,URI="https://tpN.qnlycc.cn/videos5/{hash}/crypt.key?auth_key=…",IV=0x…
   → 分片 .ts 实测 200 / 1 680 544 B
```

- m3u8 与分片**无需 Referer** 即可取（本机实测），但仍然给播放链带上 `Referer: https://huangguoai.com/` 更稳。
- `auth_key` 约 1 小时过期 → 必须**播放前现取** playback，不能长期缓存直链；
  所以章节 URL 存的是 playback 相对地址，`getPages()` 时才换真链。
- 详情里的 `video_url` 自 2026-11-20 起恒为空，**不再**作为第 1 集预置地址；
  `episodes[]` 缺失时按 `episode_count` / `total_episodes` 合成 1..N 集即可。

## 四之二、标签筛选：只有 4 个频道是真的（2026-11-04 补充实测）

应用会把内容上的 `tags`（`明星` / `都市` / `换脸` …）作为列表顶部的过滤标签回传，
而站点只有 4 个频道具备真实筛选能力，其余「标签」都是 SEO 门面：

| 候选接口 | 实测结果 |
|---|---|
| `/api/tags` | 200，返回 10 个分类 / 156 个标签（`name` / `slug` / `cate_id`），**只能用于取名字↔slug** |
| `/api/videos?tag_id=929` | 忽略参数，`total` 仍是全站 3222 |
| `/api/videos?tag=都市` / `tag_slug=dushi` | 忽略参数，返回全站流 |
| `/api/videos/tag/{slug}` | `200` 但 `items: []` |
| `/api/tags/{slug}/videos`、`/api/tag/{slug}` | 404 |
| `/tag/{slug}/`、`/tag/{slug}/page/{n}/`（SSR 页面） | 200，标题与分页器（`data-pages`）都是该标签的，但**卡片不按标签过滤**：`/tag/fanzui/` 首页 4 张卡片里 3 张没有 `fanzui`；`/page/2/`、`/page/3/` 与第 1 页共享前 4 条 id |

结论：**不要**去解析标签页，否则等于伪造筛选能力。实现上：

- 频道标签：按 `key` / 完整标题 / 去掉 `AI` 前缀的标题（`换脸` → `ai-huanlian`）解析到分类接口；
- 其余内容标签：退化为 `/api/search?q={标签名}` 关键词搜索，保证「点了标签列表会收窄」，而不是原样返回全站流。

## 五、与仓库契约的映射

| 仓库能力 | 黄果接口 |
|---|---|
| `getListPage(page, order, filter)` | `/api/videos`（search 时 `/api/search`，频道标签时 `/api/videos/category/{cate}`，其余标签退化为搜索） |
| `SortOrder.UPDATED` | `sort=new` |
| `SortOrder.POPULARITY` | 默认流（`/api/videos`，实测即热度倒序） |
| `availableTags` | 4 个 v1 分类：AI成人短剧 / AI成人漫剧 / AI换脸 / AI魔改 |
| `getDetails()` | `/api/videos/{id}` → `episodes[]` 转 `ContentChapter` |
| `ContentChapter.url` | `/api/videos/{id}/episodes/{ep}/playback`（站内相对，与域名无关；老写法 `.../play?ep={n}` 仍可反解） |
| `getPages()` | `POST` 该章节地址 → `data.streams.main` → 单个 `ContentPage(url = m3u8)` |
| `Content.rating` | `score / 10f`（0–1） |
| `Content.state` | `is_finished` → `FINISHED` / `ONGOING` |
| `Content.contentRating` | `ContentRating.ADULT` |
| 封面解密 | `intercept()` 拦截路径扩展名为图片的响应并解密（不按 host 匹配，见下） |

**不要**按 host 泛匹配图片 CDN：该域族同时承载 `crypt.key`（16 B 裸密钥）和 `.ts` 分片，
误解密会把播放打坏。2026-11-20 起改为**只看路径扩展名**（`.jpg/.jpeg/.png/.webp/.gif`），
因为站方会轮换图片 CDN 域名（`pic.drleao.cn` → `pic.qnlycc.cn`），白名单必然滞后。
`.ts` / `crypt.key` / `.m3u8` 都不带图片扩展名，天然不会被误拦；解密失败则原样透传。

## 六、实施计划（预估 1 个工作日）

1. `src/main/kotlin/org/skepsun/kototoro/parsers/site/zh/HuangguoDrama.kt`
   - `@ContentSourceParser("HUANGGUO", "黄果短剧", "zh", type = ContentType.HENTAI_VIDEO)`
   - `PagedContentParser(context, ContentParserSource.HUANGGUO, pageSize = 20)`，`configKeyDomain = ConfigKey.Domain("huangguoai.com")`
   - `availableSortOrders = EnumSet.of(UPDATED, POPULARITY)`
   - `filterCapabilities = ContentListFilterCapabilities(isSearchSupported = true, isMultipleTagsSupported = false)`
2. `HuangguoImageDecoder.kt`（AES-CBC 解密 + 魔数探测 + `decodeResponse`，与 `BaipiaoguaiImageDecoder` 同构）。
3. 离线夹具：`src/test/resources/fixtures/huangguo/{list,detail,play}.json` + 一份脱敏密文封面。
4. 测试：`HuangguoDramaTest`（列表 / 详情 / 集数 / 播放地址 / 封面解密）+ `HuangguoDramaIntegrationTest`
   （`HUANGGUO_INTEGRATION_TEST=1` 才跑）。
5. 跑 `./gradlew test --tests "*Huangguo*"`，并核对 `.github/summary.yaml` 新增一条 source。

## 七、风险与待确认

| 风险 | 说明 | 缓解 |
|---|---|---|
| 封面密钥可旋转 | key/iv 硬编码，站方换 key 后封面全黑 | 解密失败即原样透传（不抛异常），并在失败时记录域名+字段上下文 |
| `auth_key` 过期 | 封面/播放链带 1 小时时效 | 封面裁剪查询串（现未强制）；播放链现取 |
| 域名漂移 | 此类站点常换主域 | `configKeyDomain` + 页面支持切镜像；备用发布页 `huangguoai.pages.dev` |
| 直连可达性 | 本机（境外出口）**直连超时、经 7890 代理正常**；国内设备侧未验证 | 实机验证时补一条国内网络下的连通性结论 |
| 默认序语义 | 文档称「全站推荐流」，实测 `hot` 倒序 | 若后续语义变化，`POPULARITY` 只作近似，不作强断言 |
| 榜单不可翻页 | 固定 20 条 | 不暴露为排序项，避免「假分页」 |

### 内容性质（需要产品侧决策）

站点 5 个频道全部为成人向内容，其中 `ai-huanlian`（AI 换脸）与 `ai-mogai`（AI 魔改）
存在**对真实艺人做换脸/魔改的成人视频**（标题直接点名在册艺人）。

建议二选一：

- **A**：只接入 `ai-duanju` + `ai-manju`（虚构剧集/漫剧），不暴露换脸与魔改两个标签；
- **B**：全量接入 —— 则在源描述里明确标注内容性质，并按 `ContentType.HENTAI_VIDEO` + `ADULT` 处理。

## 八、取证边界

- 环境：本机 `curl --proxy 127.0.0.1:7890`，Chrome 131 UA，`Accept: application/json`，`Referer: https://huangguoai.com/`。
- 时间：2026-11-04 15:35–15:50 (UTC+8)。
- 已实测：`/api/videos`（size 2/3/5/20/50）、`/api/videos/category/{4 类}`、`/api/videos/12`、
  `/api/videos/12/play?ep=1,5`、`/api/search?q=女王`、`/api/ranks/hot`、封面 jpg/png 两种密文样本、m3u8 与首个 `.ts`。
- 未实测：登录态接口（`/api/account/*`、点赞/收藏/评论写入）——本次接入不需要，也未做任何账号或付费绕过。
- 采样口径：状态码、`content_type`、字节数、跳转 URL、JSON 结构与 `pagination.total`。

## 九、落地实现（2026-11-04）

已按第五节映射实现，线上三条链路（列表 / 详情 / 播放）与封面解密均已实机跑通。

| 文件 | 作用 |
|---|---|
| `src/main/kotlin/org/skepsun/kototoro/parsers/site/zh/HuangguoDrama.kt` | 解析器主体（`HUANGGUO` / 黄果短剧 / `HENTAI_VIDEO` / `ADULT`） |
| `src/main/kotlin/org/skepsun/kototoro/parsers/site/zh/HuangguoImageDecoder.kt` | 封面 AES-128-CBC 解密 + 魔数探测 + `decodeResponse` |
| `src/test/resources/fixtures/huangguo/{list,detail,play,search}.json` | 脱敏后的离线夹具 |
| `src/test/resources/fixtures/huangguo/cover.bin` | 线上真实封面密文的前 256 字节（CBC 前缀可正确解密，用来锁住 key/iv） |
| `src/test/kotlin/.../zh/HuangguoDramaTest.kt` | 8 个离线用例：URL 构造 / 列表 / 详情 / 集号规范化 / 播放地址 / 搜索 / 封面裁剪 |
| `src/test/kotlin/.../zh/HuangguoImageDecoderTest.kt` | 6 个离线用例：真实密文解密 / 往返解密 / 明文透传 / 非图片路径不拦 / PKCS#7 裁剪 |
| `src/test/kotlin/.../zh/HuangguoDramaIntegrationTest.kt` | 3 个在线用例（`HUANGGUO_INTEGRATION_TEST=1`）：列表→详情→m3u8→首个分片、搜索+频道、线上封面解密 |

实现期与调研阶段的三处偏差，均已按实测修正：

1. **详情走 API 而不是网页**：`getDetails()` 请求 `/api/videos/{id}`，`content.url` 仍保存站内相对路径
   `/video/{id}`（域名无关）；`publicUrl` 补尾斜杠成 `/video/{id}/`，否则会多吃一次 301。
2. **章节标题规范化**：`episodes[].title` 实测可整段塞剧情简介，统一改写为「第N集」，只保留 `ep_num` 作为 `number`。
3. **播放地址只透传 m3u8**：`play` 返回的 `video_key`/`video_iv` 实测为空，密钥仍在播放列表内（AES-128），
   因此 `getPages()` 直接返回 `video_url`，不做二次解密；`video_url_hevc` 仅在 `video_url` 为空时兜底。

内容策略本次取 **B（全量接入）**：4 个频道全部暴露为筛选项，并按 `HENTAI_VIDEO` + `ADULT` 标注。
若产品侧改判为 A，只需从 `HuangguoDrama.CHANNELS` 去掉 `ai-huanlian` 与 `ai-mogai` 两项，其余代码无需改动。

`.github/summary.yaml` 的 `total` 由 95 变为 96（KSP 已自动登记新源）。

## 十、2026-11-20 改版复核：封面无法加载 + 无法播放

线上反馈：封面全白、点开任意一集都放不出来。本机经 `curl --proxy 127.0.0.1:7890`
逐条复核（直连仍超时），确认是站方两次静默改版叠加，与解析器逻辑无关：

### 10.1 封面：CDN 换了域名，白名单没跟上

`list.json` / `detail.json` 的 `cover` 已从 `pic.drleao.cn` 换成 `pic.qnlycc.cn`。
旧实现的拦截条件含 host 白名单，新域名不在名单里 → 拦截器放行未解密的 AES 密文 →
`binary/octet-stream` 被当图片塞给 UI，表现为封面空白。

复核 key/iv **未变**（同一份 174 816 B 密文，`openssl enc -d -aes-128-cbc -nopad`
解出 `FFD8FFE0` JPEG + 合法 PKCS#7 尾部）。修复方式是**放弃 host 匹配、只看路径扩展名**，
理由见第五节：`.ts` / `crypt.key` / `.m3u8` 不带图片扩展名，扩展名判据本身已经足够排他，
而 host 白名单只要站方换一次 CDN 就会再次失效。

### 10.2 播放：`/play` 接口被 `/playback` 取代

| 请求 | 返回 |
|---|---|
| `GET /api/videos/12/play?ep=1` | `status:1`，但 `video_url` / `video_key` / `video_iv` **全为空** |
| `GET /api/videos/12`（详情） | `video_url` 同样为空 |
| `POST /api/videos/12/episodes/{ep}/playback`，body `{}` | `data.streams.main` = 可用 m3u8（ep 1 / 10 / 21 均验过） |

因此 `getPages()` 改为对章节相对地址发 `POST`，再按
`streams.main → streams.hevc → video_url → video_url_hevc` 依次取第一个非空值。
章节 URL 随之改成 `/api/videos/{id}/episodes/{ep}/playback`；`episodeRefOf()` 同时保留
老写法 `/api/videos/{id}/play?ep={n}` 的反解分支，升级前已入库的章节列表无需重新添加收藏。

注意详情页路径 `/video/{id}` 与接口路径 `/api/videos/{id}` 是两段不同的正则，
老写法反解必须用接口段匹配，否则拿不到影片 id。

### 10.3 其他同步的实测结论

- **`page` 从 1 起**：`page=0` 直接 400，`PagedContentParser` 侧设 `firstPage = 1`。
- **`sort=popular` 不再接受**：`/api/site/sorts/video` 仍把它列在返回里，但带它请求列表返回 400；
  `POPULARITY` 因此改为不带 `sort` 参数（默认序即热度倒序）。
- **两个频道下线**：`ai-huanlian` / `ai-mogai` 返回 **410 内容已下线**，已从 `CHANNELS` 移除；
  历史保存的这两个标签会退化为关键词搜索而不是报错。
- **`.ts` 分片的偶发 403**：新入库剧集的首个分片偶尔返回 403（`Server: Lego Server`，
  无 `Set-Cookie`、不要求额外鉴权头），稍后同一 URL 在 `tp1..tp8` 上全部 200。
  判定为边缘节点/出口线路抖动，**不是**解析器缺陷，播放链路不做重试逻辑。

### 10.4 复核命令

```bash
./gradlew test --tests "*Huangguo*" --no-daemon   # 离线夹具全覆盖
HUANGGUO_INTEGRATION_TEST=1 ./gradlew test --tests "*Huangguo*IntegrationTest" --no-daemon
```

离线夹具已按新协议重录：`play.json` 换成 `streams.main` 信封、`detail.json` 的 `video_url` 置空并补
`total_episodes`，另加 `detail_no_episodes.json` 锁住「episodes 缺失时按集数合成章节」的分支。

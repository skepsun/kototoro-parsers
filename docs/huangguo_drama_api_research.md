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
| 分页 | `page` / `size` 真分页（3218 条 / 161 页 @ size=20） | ✅ `PagedContentParser` |
| 搜索 | `/api/search?q=` 可用（`女王` → 40 条 / 14 页） | ✅ 真搜索，非假搜索 |
| 排序 | `sort=new` 生效；未知值静默回落默认（热榜序） | ✅ 仅暴露 2 种 |
| 内容类型 | 全部为成人向 AI/真人竖屏短剧 | ⚠️ `ContentType.HENTAI_VIDEO` |

## 一、域名与请求约定

| 项 | 值 |
|---|---|
| 主域名 | `https://huangguoai.com`（`configKeyDomain`） |
| 备用 | `https://huangguoai.pages.dev/`（地址发布页，非 API 镜像） |
| 图片 CDN | `pic.drleao.cn`（镜像曾见 `new.drleao.cn` / `new2.drleao.cn` / `image.drleao.cn`） |
| 视频 CDN | `yd-hls.hzjvlo.cn` / `yd-lphu-hls.hzjvlo.cn` / `tp2.drleao.cn` |
| 必要头 | `User-Agent`（Chrome 桌面）、`Accept: application/json`、`Referer: https://huangguoai.com/` |

响应统一信封：`{"data": …, "status": 1, "msg": "", "req_time": …}`，`status == 1` 为成功。
`/api/**` 另有一个 `POST /api/edge/request` 边缘信封（`{method, path, body(base64), content_type}`），
**本站解析器不需要它**：公开 GET 直连即可，信封只为带 Bearer 的登录态接口服务。

## 二、可用的读取接口（均已实测）

| 用途 | 请求 | 说明 |
|---|---|---|
| 全站流 | `GET /api/videos?page=&size=[&sort=new]` | 默认序≈按 `hot` 倒序；`sort=new` 为最新 |
| 分类流 | `GET /api/videos/category/{cate}?page=&size=[&sort=new]` | cate ∈ `ai-duanju` / `ai-manju` / `ai-huanlian` / `ai-mogai` |
| 搜索 | `GET /api/search?q={kw}&page=&size=` | 参数名是 `q`（传 `keyword` 返回 `status:0`） |
| 详情 | `GET /api/videos/{id}` | 含 `episodes[]`、`cover`、`tags`、`score`、`is_finished` |
| 分集播放 | `GET /api/videos/{id}/play?ep={n}` | 返回 HLS m3u8；`page_texts` 可省略 |
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
GET /api/videos/12/play?ep=1
→ https://yd-lphu-hls.hzjvlo.cn/videos5/{hash}/{hash}.m3u8?auth_key=…&via=hgdj
   #EXT-X-KEY:METHOD=AES-128,URI="https://tp2.drleao.cn/videos5/{hash}/crypt.key?auth_key=…",IV=0x…
   → 分片 .ts 实测 200 / 1 680 544 B
```

- m3u8 与分片**无需 Referer** 即可取（本机实测），但仍然给播放页带上 `Referer: https://huangguoai.com/` 更稳。
- `auth_key` 约 1 小时过期 → 必须**播放前现取** `play`，不能长期缓存章节地址；
  建议章节 URL 存站内相对地址 `/api/videos/{id}/play?ep={n}`，在 `getPages()` 时才换真链。
- 详情里的 `video_url` 是第 1 集的预置 m3u8，可直接复用（少了 `ep=1` 的一次请求）。

## 五、与仓库契约的映射

| 仓库能力 | 黄果接口 |
|---|---|
| `getListPage(page, order, filter)` | `/api/videos`（search 时 `/api/search`，带 tag 时 `/api/videos/category/{cate}`） |
| `SortOrder.UPDATED` | `sort=new` |
| `SortOrder.POPULARITY` | 默认流（`/api/videos`，实测即热度倒序） |
| `availableTags` | 4 个 v1 分类：AI成人短剧 / AI成人漫剧 / AI换脸 / AI魔改 |
| `getDetails()` | `/api/videos/{id}` → `episodes[]` 转 `ContentChapter` |
| `ContentChapter.url` | `/video/{id}/{ep}`（站内相对，与域名无关） |
| `getPages()` | `/api/videos/{id}/play?ep={ep}` → 单个 `ContentPage(url = m3u8)` |
| `Content.rating` | `score / 10f`（0–1） |
| `Content.state` | `is_finished` → `FINISHED` / `ONGOING` |
| `Content.contentRating` | `ContentRating.ADULT` |
| 封面解密 | `intercept()` 拦截 `*.drleao.cn` 的 `.jpg/.jpeg/.png/.webp/.gif` 响应并解密 |

**不要**按 host 泛匹配 `drleao.cn`：`tp2/tp3.drleao.cn` 同时承载 `crypt.key`（16 B 裸密钥）和 `.ts` 分片，
误解密会把播放打坏。按「host ∈ 图片 CDN 白名单 + 扩展名」或沿用 `DdManhuaParser` 的 `#fragment` 标记法。

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

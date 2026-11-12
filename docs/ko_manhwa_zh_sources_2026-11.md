# 中文汉化韩漫站点调研（2026-11-11）

范围：**中文汉化/搬运的韩国 webtoon（韩漫）站点**，不含韩国原站（naver/kakao/lezhin 等）。
方法：从 Keiyoushi `extensions-source` 的 `src/zh`、`src/ko` 现役源取当前域名 → 本机 7890 代理实测（Chrome142 UA，跟随重定向，记录状态码/体积/title/韩漫关键词/作品链接数）→ 对重点候选深挖列表/详情/阅读页结构 → 与仓库现有注册源（`.github/summary.yaml` total=115，其中 `site/zh/` 70 个解析器文件）逐一对表。

> 证据等级标注：**[实测]** = 2026-11-11 本机代理实测一手响应；**[上游]** = Keiyoushi 仓库当前 baseUrl 佐证；**[记忆]** = 既往调研结论；**[未证]** = 未验证。

---

## 一、结论摘要

1. **仓库原本没有一个以"韩漫"定位的专属解析器，只有"顺带覆盖"**：`DONGMANMANHUA`（咚漫，韩国 Naver Webtoon 官方中文版）、`MXS_`（漫小肆，实为韩漫聚合站群）、`JMCOMIC`/`PICACG`/`COPYMANGA`（综合站含韩漫分区）。**本轮已补上第一个专属源 `NNHANMAN`（鸟鸟韩漫）。**
2. **最大的意外收获：「92韩漫」与已实现的 `MXS_`（漫小肆）是同一套引擎，扩域即可覆盖**，不需要新解析器。实测 `mxshm.top`、`ikanmh.xyz`、`jjmhw2.top`、`jjmhw12.top` 四域 title 完全一致（"漫小肆韓漫-92韩漫最新地址-顶通免费地址-玩漫最快更新…"），路径同为 `/book/{id}`、`/chapter/{id}`、`/booklist?tag=&area=&end=&page=`，详情页 SSR 出全量章节（样例 85 章）。**[实测]**
3. **真正未覆盖、且结构最干净的新候选是「鸟鸟韩漫」`nnhanman.xyz`**：纯韩漫定位，全 SSR，`/comic/{slug}.html` 详情 + `/comic/{slug}/chapter-{id}.html` 阅读页，阅读页 124 张图走 `data-src` + `data-index` 有序输出。**[实测] → 已实现为 `NNHANMAN`，见 §4.1 与 §五 P1。**
4. **韩国官方中文平台「TOPTOON」`toptoon.net` 是 Vue SPA**（HTML 里只有 `{{link}}`、`/comic/epList/{{comic_id}}` 模板占位，无 `__NEXT_DATA__`），且属付费/等就免费的正版平台，接入需逆向接口并只取免费话，优先级最低。
5. 此前被否决的香香腐宅/漫蛙/鸟鸟韩漫三站，本轮**代理实测已可达**（boylove 200/256KB、manwa2 200、nnhanman 200），可重新评估；卡通18、manwa.me、18mh、MyComic、ikmmh 仍 403。**其中 boylove 已实现为 `BOYLOVE`（BL 韩漫细分源），见 §4.5 与 §五 P2；manwa2 已实现为 `MANWA2`，见 §4.4 与 §五 P3。**
6. **本轮四个优先级（P0~P3）已全部落地**：`MXS_` 扩域、`NNHANMAN`、`BOYLOVE`、`MANWA2` 四个源，仓库注册总数 115 → 118。仅剩 P4（TOPTOON，SPA + 正版付费）未做。

---

## 二、仓库现有韩漫覆盖盘点

| 源 | 类目 | 与韩漫的关系 | 覆盖情况 |
|---|---|---|---|
| `DONGMANMANHUA`（咚漫 `dongmanmanhua.cn`） | 漫画 | **韩国 Naver Webtoon 官方中文版**，正版韩漫主渠道 | ✅ 已实现 |
| `MXS_`（漫小肆 `www.mxshm.top`） | HENTAI_MANGA | **实为韩漫聚合站群**，与 92韩漫/ikanmh 同引擎 | ✅ 已实现，但只注册了 1 个域名 |
| `JMCOMIC`（禁漫） | HENTAI_MANGA | 含大量韩漫汉化；`hanman18.com` 是其同模板镜像 | ✅ 已实现 |
| `PICACG`（哔咔） | HENTAI_MANGA | 含韩漫分区 | ✅ 已实现 |
| `COPYMANGA`（拷貝漫畫） | 漫画 | 有韩漫分区（`/category/asia` 系） | ✅ 已实现 |
| `GUAZI`（瓜子漫画） | 漫画 | 国产/韩漫聚合 | ✅ 已实现 |
| `WNACG` / `MH18` / `MANHUASHE` 等 | 综合 | 零星含韩漫 | ✅ 已实现 |
| `JIANG...`（`www.liumanhua.com`） | 综合 | 与 `DDMANHUA` 同域预设 | ✅ 已实现 |

**结论：韩漫内容"能看得到"，但缺一个对韩漫用户有辨识度的专属源。**

---

## 三、候选清单与可达性实测

实测条件：`curl -x http://127.0.0.1:7890`，Chrome142 UA，`-L` 跟随，20s 超时，2026-11-11。

> ⚠️ 本机直连白名单只放行 github/cloudflare 等少数域，**直连 000 不代表站点在国内不可达**，只是本环境网络策略所致；代理 200 才是有意义的可达证据。

| # | 站点 | 域名 | 代理实测 | 体积 | 结构判定 | 仓库 |
|---|---|---|---|---|---|---|
| 1 | **鸟鸟韩漫** | `nnhanman.xyz` | **200** | 45KB | **全 SSR**，`/comic/{slug}.html`；阅读页 124 图 `data-src` | ✅ 已实现 `NNHANMAN` |
| 2 | 92韩漫 / 漫小肆系 | `ikanmh.xyz`、`jjmhw2.top`、`jjmhw12.top` | **200** | 61KB | **与 `MXS_` 同引擎**，`/book/{id}`、`/chapter/{id}` | 🟡 扩域可覆盖 |
| 3 | **TOPTOON 官方中文** | `www.toptoon.net` | **200** | 73KB | Vue SPA，`{{link}}` 占位，无 SSR 数据 | ❌ 未覆盖（需逆向） |
| 4 | 漫蛙2 | `manwa2.com` | **200** | 69KB | 列表/详情/目录全 SSR（`/comic/{数字}`、`/chapter/{书}/{话}`）；阅读页图片在 AES 加密的 `params` 里 | ✅ 已实现 `MANWA2` |
| 5 | 香香腐宅（BL 韩漫） | `boylove.cc` | **200** | 256KB | 详情 SSR；列表/搜索走 JSON 接口 | ✅ 已实现 `BOYLOVE` |
| 6 | Hanman18（禁漫镜像） | `hanman18.com` | **200** | 20KB | 禁漫天堂同模板 | 🟡 与 `JMCOMIC` 重叠 |
| 7 | 嬉皮漫画 | `m.hipmh.com` | **200** | 129KB | 作品链接为 JS 模板 `{{value['id']}}`，非 SSR | ❌ 难解析 |
| 8 | 哔咔 web | `m.bikamanhua.com` | 200 | 8KB | 官方 App 已有 `PICACG`，web 价值低 | 🟡 重叠 |
| 9 | 如漫画 | `m.rumanhua.org` | 200 | 5KB | 无作品链接（壳页） | ❌ |
| 10 | 177漫畫 | `www.177pica.com` | 200 | 7KB | 无作品链接（壳页） | ❌ |
| 11 | 漫本 | `www.manben.com` | 200 | 14KB | 原创发行平台，非韩漫 | — |
| 12 | ACGN 动漫戏说 | `comic.acgn.cc` | 200 | 16KB | 综合，368 链接，韩漫非主业 | — |
| 13 | 卡通18 | `www.cartoon18.com` | **403** | 3KB | CF 拦截 | ❌ |
| 14 | 漫蛙主站 | `manwa.me` | **403** | 3KB | CF 拦截 | ❌ |
| 15 | 18mh | `18mh.org` | **403** | 3KB | CF 拦截 | ❌ |
| 16 | MyComic | `mycomic.com` | **403** | 1KB | Cloudflare challenge | ❌ |
| 17 | ikmmh | `*.ikmmh.com` | **403** | — | 主域已 404 | ❌ |
| 18 | 读漫屋 | `m.dumanwu1.com` | 000 | — | 双路径不通 | ❌ |
| 19 | 如漫画2 | `m.rumanhua2.com` | 000 | — | 双路径不通 | ❌ |
| 20 | 热拉漫画 | `m.2024manga.com` | 404 | 0 | "服務器升級中" | ❌ |
| 21 | 永久地址发佈页 | `freexcomic.com` | 200 | — | 跳转导航页，无内容 | — |

---

## 四、重点候选结构详解

### 4.1 鸟鸟韩漫 `nnhanman.xyz` —— 最推荐

**定位**：纯韩漫，"最全最新无遮挡无删减韩漫"，成人向（`ContentRating.ADULT` / `ContentType.HENTAI_MANGA`）。**[实测]**

| 环节 | 证据 | 判定 |
|---|---|---|
| 首页 | 45.6KB，`kw(韩漫)=10`，作品链接 137 | ✅ SSR |
| 导航 | `/comics`（全部漫画）、`/ranking`（排行）、`/update`（更新）、`/catalog.php`（站点级更新列表，分页 18 部/页） | ✅ 列表入口明确 |
| 详情 | `/comic/{slug}.html`，23.5KB，title「代孕媽媽无遮挡版全集免费在线阅读 - 鸟鸟韩漫」，含作者/连载中/更新时间元信息 | ✅ SSR |
| **章节** | 详情页 `#mh-chapter-list-ol-0` 内 SSR 出**全量章节**，无翻页无接口：`mi-mi-jiao-xue` 336 话一页到底（52KB），`jie-qi-tiao-yue` 70 话、`lie-yan-guan-li-yuan` 64 话同样一次到位；顺序为**倒序**（最新在前），每条 `<li><a href="/comic/{slug}/chapter-{id}.html"><span>{标题}</span></a></li>` | ✅ **无缺口** |
| 阅读 | 57.9KB，**124 张图**，`data-src="https://img.nnpic.xyz/upload_s/..."`（注意是 `data-src` 而非 `data-original`）+ `data-index="0..N"`，与站点域分离但在同一 CDN | ✅ 图片清单干净、可排序 |
| 反爬 | 无 CF、无签名、无加密；页面底部有 `new Function(b)` 混淆脚本但图片与章节结构不受影响 | ✅ 低风险 |

> **更正（2026-11-11 二次实测）**：上一版记录"详情页只内联最近 7 话、章节全表位置未知"是**样本选错导致的误判**——当时的样本 `dai-yun-ma-ma`（代孕媽媽）本身就只有 7 话。换长连载复核后确认全量章节就在详情页里，`index_2.html` / `?page=2` / `chapter_list.html` 那些 404 探测全部没有必要。另更正一处：`/catalog.php` 不是"站点级更新列表 + 被忽略的 bid 参数"，它是**搜索接口**（详情页搜索表单 `action="/catalog.php"`）。

**结论：nnhanman 已无阻塞项，可直接进实现。**

**实现已完成（2026-11-11，P1）**：`src/main/kotlin/org/skepsun/kototoro/parsers/site/zh/NnhanmanParser.kt`，注册 id `NNHANMAN`（"鸟鸟韩漫"，`zh`，`ContentType.HENTAI_MANGA`）。落地时补齐的四处此前未记录的细节：

1. **分类列表是路径式组合**，不是查询参数：`/comics/{题材}/ob/{排序}/st/{状态}`，第 2 页起追加 `/page/{n}`，每页 18 条。题材为 `all` 或 20 个中文题材（`正妹/恋爱/出版漫画/肉慾/浪漫/大尺度/巨乳/有夫之婦/女大生/狗血劇/同居/好友/調教/动作/後宮/不倫/3D/校園/耽美/日漫`，中文需百分号编码进路径段）；排序 `time`/`hits`；状态 `all`/`serialized`/`completed`。三种组合已实测 200 且标题随参数变化。**[实测]**
2. **搜索是分页的**：`/catalog.php?key={关键词}&page={n}` 实测有效（第 2 页返回的上一页链接指向 `/search/a/page/1`），站点另有等价路径形式 `/search/{关键词}/page/{n}`。先前"搜索不分页"的判断来自单结果样本（空 `ul`）导致误判。**[实测]**
3. **`<h1>` 会被 HTML 解析器挤出 `.txtItme2`**：站点把 `<h1>《标题》</h1>` 写在 `<p class="txtItme2">` 内部，`<p>` 遇块级元素提前闭合，于是 `.txtItme2 h1` 在真实 DOM 里选不到——必须从 `div.sub_r h1` 等外层取，并剥掉书名号。
4. 列表页与搜索结果共用 `ul.col_3_1 > li` 卡片结构（`a.txtA[title]` 取标题、`img[src]` 取封面绝对地址），可直接一套解析；`ul#mh-chapter-list-ol-0 > li > a` 是全量目录（倒序，需反转后编号）。

阅读页 `img[data-src]` 共 124 张且全部位于 `#currentCache … #m_r_bottom` 之间，`data-index` 与 DOM 顺序完全一致（0..123 唯一递增），页面另外 3 个 `<img>` 分别是站标、分隔 gif 和 Yandex 统计像素——用 `img[data-src]` 即可精确命中。

### 4.2 92韩漫 / 漫小肆系 —— 扩域即可，零新代码

**[实测]** 四域同源证据：

```
www.mxshm.top   title: 漫小肆韓漫-92韩漫最新地址-顶通免费地址-玩漫最快更新-顶通破解版-歪歪漫画最新地址…
ikanmh.xyz      title: （完全一致）
jjmhw2.top      title: （完全一致）
jjmhw12.top     title: （完全一致）

ikanmh.xyz/booklist?tag=&area=-1&end=-1&page=1 → 200，28 个 /book/{id}
ikanmh.xyz/book/1054 → 200，85 个 /chapter/{id}（全量章节 SSR）
```

仓库 `MxsParser` 现注册域名仅 `www.mxshm.top`（`site/zh/MxsParser.kt:44`），路径 `/booklist`、`/book/{id}`、`/chapter/{id}` 与站点完全一致（`MxsParser.kt:111,174,188,258`）。

**已落地（2026-11-11）**：`MxsParser.configKeyDomain` 现为四域预设，默认域保持 `www.mxshm.top` 不变（`defaultValue` 取第一个，老用户域名配置不受影响）：

```kotlin
override val configKeyDomain = ConfigKey.Domain(
    "www.mxshm.top",
    "ikanmh.xyz",
    "jjmhw2.top",
    "www.jjmhw12.top",
)
```

四域五路径（书单 / 详情 / 阅读 / 图片 / 搜索）响应**字节数完全一致**，含图片 18480B 一致；图片走 `/static/upload/book/{bookId}/{chapterId}/{n}.jpg`，四个域各自都能直接取到图，因此 `getPages` 把图片 host 重写为当前域名是安全的，切域不裂图。同时把 `buildListUrl` / `parseComicList` / `parseChapters` / `parsePages` 提成 `internal` 纯函数，配 `MxsParserTest` 7 个离线用例（域名预设、分页 URL 跟随域名、卡片解析、全量章节、图片 host 重写）。

> 未改动对外显示名 `漫小肆`：改注册名会影响用户已收藏内容的归属识别，价值不大。若要突出韩漫定位，建议另起独立注册（见 P1），而不是改这个源的名字。

### 4.3 TOPTOON 官方中文 `toptoon.net` —— 正版但难接

- 200 / 73.6KB，title「TOPTOON 漫畫 條漫-國際官方中文版-韓國最新漫畫-線上免費看」**[实测]**
- HTML 内只有 Vue 模板占位：`{{link}}`、`/comic/epList/{{comic_id}}`、`/comic/epList/{{id}}`，`__NEXT_DATA__`/`__NUXT__` 计数为 0 → **纯客户端渲染**，作品列表与章节都要走 API。
- 已知路由：`/search`、`/search/genre`、`/my/comic/library`、`/payment`、`/freesale`（等就免费）。
- **限制**：正版付费平台，按仓库约定（AGENTS.md「不绕过账号权限、付费内容」）只能解析公开/免费的等待开放话；建议不排入近期计划。

### 4.4 漫蛙2 `manwa2.com` —— 与滴答/古风同内核的 MCCMS 站

- 首页 69KB SSR，作品 `/comic/{数字 id}` 有 12+ 条；详情 25.7KB SSR。主域 `manwa.me` 403，只有 `manwa2.com` 通。**[实测]**

> **更正（2026-11-12 实现期复核）**：上一版写的「章节在 `#chapter_indexes` tab 容器、不在首屏 HTML、需逆向异步数据源」**是误判**。四处抽样（含 924 章的 `418962`）都确认**全量章节就在详情页 SSR 里**：`ul#detail-list-select > li > a.chapteritem[href=/chapter/{书}/{话}][title]`，顺序**新→旧**，反转即阅读顺序；页面上「倒序 / 展开全部章节」都是纯前端行为（前者改 `sort-btn` 的 mode 后重排，后者只放开容器 `max-height`），不触发任何额外请求。`#chapter_indexes` 只是老模板残留容器。

- **列表**：分类页 `/category` 每页 30 条。筛选是**单段路径**且各段互相独立、顺序不敏感（`/category/list/3/finish/2` 与 `/category/finish/2/list/3` 结果一致，已实测）：`list/{1 国产,2 日本,3 韩国,4 欧美}`、`city/{42 内地,43 港台,44 韩国,45 日本,134 欧美}`、`theme/{32…37}`、`quality/{38…41}`、`copyright/{46 首发,47 独家}`、`finish/{1 连载,2 完结}`、`tags/{867…966}`（含 889 韩漫、950 非H）。翻页 `/category[/…]/page/{n}`，越界会 302 到 `/err/404`（解析为空列表即收敛）。卡片 `ul.manga-list-2 > li`：标题 `p.manga-list-2-title a`，简介 `p.manga-list-2-tip`，封面 `img[data-original]` 或 `img[src]`（个别未出图条目只给 `packs/mccms/empty.png` 占位图，要当无封面处理）。
- **排序**：`/custom/top`（人气总排行）、`/custom/update`（最新更新）各固定 30 条且**不分页**（`/custom/update` 上加 `page` 参数无效），所以只当第一页用，带筛选或翻页回落 `/category`。
- **搜索**：`/search?key={关键词}`（参数名就是 `key`，由 `search.js` 确认），服务端渲染 `ul.book-list > li`：标题 `p.book-list-info-title`、简介 `p.book-list-info-desc`、作者 `span.book-list-info-bottom-item`（「作者：X」）、状态 `span.book-list-info-bottom-right-font`。**该页没有分页控件，`page` 参数被忽略**（同一关键词的第 2 页响应与第 1 页**字节完全一致**，43336B），只给 30 条，因此搜索固定单页。另外 `/search` 连发会 429（18 字节空体），探测需间隔 1.5~2s。
- **详情**：标题 `h1.detail-main-info-title`，封面 `div.detail-main-cover img[data-original]`，信息为多行 `p.detail-main-info-author`（`span.detail-main-info-author-field` 标签 + `span.detail-main-info-value` 取值：作者/更新状态/最新章节/地区/类别），简介 `p.detail-desc`。**没有分类标签锚点**（`div.detail-main-info-class` 不存在），标签只能由「地区」（如 `国产漫画`）与空格分隔的「类别」（如 `连载中 国漫`）文本推导。`#adult-content` 区块存在但四个样本都没有 `#adult-list-select`，成人章节混在普通目录里。
- **阅读页**：DOM 里**没有图片**（`#cp_img > div.img-content` 是空的，`img[data-r-src]` 也是空选择器），SSR 只给一段 `var params = '<Base64>'`。算法（与滴答/古风同密钥，可直接复用 `AesCbcDecoder.decodeJsonWithPrefixedIv`）：`raw = Base64.decode(params)`；`iv = raw[0..16]`；`ciphertext = raw[16..]`；`key = UTF-8("9S8$vJnU2ANeSRoF")`；AES-CBC 解密 → UTF-8 → `JSON.parse`。明文键 `{host, source_id, comic_id, comic_down, chapter_id, images[], lazy}`，**`host` 必须等于当前请求 Host 否则按空处理**（站点用它防跨站盗链）。本站 `source_id="12"` 但 `images[]` 给的是**绝对地址**（`https://img1.baipiaoguai.org/...webp`），逐张实测为**明文 webp 200**，无需 AES 图片解密；图床按 `Referer: https://manwa2.com/` 做防盗链——不带 Referer 返回 403（UA 无关，且 Referer 必须是站点根域、不能是图床域），带 Referer 返回 200 `image/webp`。**[实测]**（解密参数由 Node 侧追踪 `chapter.js` 的 `CryptoJS` 调用还原，并用 Python 独立复算校验）
- **实现**：`site/zh/Manwa2Parser.kt`，注册 id `MANWA2`（"漫蛙"，`zh`，`ContentType.HENTAI_MANGA`），域名走 `configKeyDomain`（`manwa2.com` / `www.manwa2.com`）；`SortOrder.UPDATED` 走 `/custom/update`、`SortOrder.POPULARITY` 走 `/custom/top`，其余与筛选一起走 `/category` 分页；`getPages` 在 Kotlin 侧解 `params`（不执行站点 JS），页面 headers 带 `Referer`。**[实测]**

### 4.5 香香腐宅 `boylove.cc`

- 200 / 256KB，BL/耽美向（含大量韩漫汉化），详情 `/home/book/index/id/{id}`，首页有 18 条作品链接。**[实测]**
- 与仓库 `BAIHEHUI`（百合会/yamibo，GL）类目相近但站点不同，不构成重复。
- 上游 Keiyoushi 该源还带镜像 `boylove4.xyz` 等，多域名需一并处理。**[上游]**
- **列表/搜索是 JS 渲染的，DOM 里 0 条 `/home/book/index/id/` 链接**，必须直接打 JSON 接口 **[实测]**：
  - 列表 `GET /home/api/cate/tp/{cate}-{tag}-{progress}-{order}-{page}-{is18}-1-{vip}?mt={mt}`；`cate` 恒为 1、`tag` 恒为 0（站点未公开 tag id 表）。`mt` 才是真正的漫画来源判别参数（1 韩漫 / 2 日漫 / 3 国漫 / 0 全部）——URL 里那个 `m_type` 路径段 JS 从不发送，别照抄。**分类实测计数：全部 16648 / 韩漫 2582 / 日漫 3672 / 国漫 717**。响应 `{code, result:{lastPage, listCount, list[24]}}`，注意 `lastPage` 是**布尔**（`false`）而非页码，翻页只能靠列表长度收敛，`listCount` 才是总数。
  - 搜索 `GET /home/api/searchk?keyword={q}&type=0&pageNo={p}`（`type=0` 漫画 / `type=2` 小说），18 条/页；**空结果返回的 JSON 里没有 `list` 键**，只给 `{firstPage,lastPage,code,test}`，解析时要按可空处理。
  - 条目字段：`id / title / image`（相对路径 `/bookimages/…webp`，需前缀 `https://img.boylove.cc`）`/ auther`（逗号分隔）`/ mhstatus`（0=连载，其余=完结）`/ last_chapter_title`。
- **详情页章节内嵌在 JS 里**：`function getChapterList(asc) { let data = JSON.parse("{\"list\":[…],\"history\":{}}"); … }`，正则抠出这段字符串字面量再 `unescapeJson()` 转 `JSONObject` 即可。该列表**已经是阅读顺序（旧→新）**，参数 `asc` 只是原样传给 `template("comic_chpater_list", data)`，并不排序。章节对象 `{id, isvip, title, score, create_time}`，`isvip=="1"` 为付费话，直接丢弃（编号按过滤前的索引取，保留站内真实位置）。**[实测]**
- 详情页其余字段：封面 `a.play[data-original]`（绝对 URL）、标题 `div.stui-content__detail h1`、连载状态取第一个**不含 `<i>`** 的 `p.data`、作者取 `<i>` 含「作者」的 `p.data` 下的 `a`（页面里还留着一份被注释掉的旧作者行，Jsoup 会正确跳过）、标签是 **标签 `p.data` 内**的 `a.taga`（顶层 `a.taga` 也会匹配到「開始閱讀」按钮，必须限定作用域）、简介 `p.desc.detail .detail-text`。`og:image` 不可靠，3 个样本里 2 个缺失。**[实测]**
- **阅读页** `/home/book/capter/id/{chapterId}`（站点自己拼错成 `capter`）：图片在 `div.reader-cartoon-image img`，真地址在 `data-original`（`src` 只是 `/static/images/load.png` 占位），host `img.boylove.cc`，后缀 `?w=W_H` 每本书不同（`2250_3200` / `650_1040`，甚至有小数 `650_654.225`）。末段还有一个站内广告位带 `data-original=""`，必须过滤空值；两个样本分别 117/121 张真实图、零重复。图片**带不带 Referer 都返回 `200 image/webp`**。**[实测]**

### 4.6 Hanman18 `hanman18.com`

- title「Hanman18.com - 禁漫天堂」，20KB / 170 链接，禁漫系同模板镜像。**[实测]**
- 与已实现的 `JMCOMIC`（18comic 系列）内容重复度高，**不建议单独内置**，可作为 `JmComicParser` 的备选域名评估。

---

## 五、建议实施优先级

| 优先级 | 动作 | 成本 | 理由 |
|---|---|---|---|
| **P0 ✅ 已完成** | `MxsParser` 扩域覆盖 92韩漫（`ikanmh.xyz` 等） | 极低 | 已验证同引擎同路径，四域五路径响应**字节完全一致**（含图片）。已落地：`configKeyDomain` 增 3 个预设域 + `MxsParserTest` 7 个用例 |
| **P1 ✅ 已完成** | 新增「鸟鸟韩漫」`nnhanman.xyz` 解析器 | 中低（章节全表已确认在详情页，无遗留探测） | 纯韩漫定位、全 SSR、图片链路干净、无 CF。已落地：`NnhanmanParser`（`NNHANMAN`，6 个域走 `configKeyDomain`）+ 4 个离线 fixture + `NnhanmanParserTest` 12 个用例 |
| **P2 ✅ 已完成** | 新增「香香腐宅」`boylove.cc` 解析器 | 中（列表走 JSON 接口、章节内嵌 JS） | 可达、SSR，BL 韩漫细分空缺。已落地：`BoyloveParser`（`BOYLOVE`，2 个域走 `configKeyDomain`）+ 列表/搜索走 JSON 接口 + `mt` 韩漫/日漫/国漫筛选 + 6 个离线 fixture（list / search / search_empty / detail / detail_vip / chapter）+ `BoyloveParserTest` 16 个用例 |
| **P3 ✅ 已完成** | 「漫蛙2」`manwa2.com` | 中（列表/详情/目录 SSR，阅读页需解 AES params） | 中文圈流量大、图源稳、与滴答/古风同内核。已落地：`Manwa2Parser`（`MANWA2`，2 个域走 `configKeyDomain`）+ 单段路径多维度筛选 + `AesCbcDecoder` 复用解阅读参数 + 6 个离线 fixture（list / ranking / search / detail / detail_many / chapter）+ `Manwa2ParserTest` 16 个用例 |
| **P4** | TOPTOON 官方中文 | 高 | SPA + 正版付费，只能取免费话 |
| 不建议 | Hanman18 / 哔咔 web | — | 与 JMCOMIC / PICACG 重复 |
| 不建议 | 卡通18 / manwa.me / 18mh / MyComic / ikmmh | — | 403 CF 拦截 |
| 不建议 | 读漫屋 / 如漫画2 / 热拉漫画 / 如漫画org / 177pica | — | 不通或纯壳页 |

---

## 六、证据与限制

- **一手实测**：2026-11-11，本机 `127.0.0.1:7890` 代理出口，Chrome142 UA，curl 20s 超时；21 个候选域 × 直连/代理双路径；对 8 个重点候选做了列表/详情/阅读页三段结构取样。
- **上游佐证**：Keiyoushi `extensions-source@main` 的 `src/zh/*/build.gradle.kts` 与 `src/ko/*` 现役 baseUrl（nnhanman→`nnhanman.xyz`、hanman18→`hanman18.com`、boylove→`boylove.cc`、cartoon18→`www.cartoon18.com`、manwa→`manwa.me`、toptoon→`www.toptoon.net`、xipimanhua→`m.hipmh.com`、ikanmh→`ikanmh.xyz`）。
- **代码侧验证（P0）**：`./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.MxsParserTest"` → 7/7 通过；风险扩大跑 `--tests "org.skepsun.kototoro.parsers.site.zh.*"` → 429 个用例、7 失败、67 跳过，7 个失败全部是 `org.koitharu...` 下的历史直连线上测试（CopyMangaAuth / CopyMangaImageLoad / CopyMangaXigelide / WnacgRead），异常为 `SocketTimeoutException` / `SSLHandshakeException` / `runTest` 超时，与本次改动无关（本次只动 `MxsParser.kt`）。**注意：不加 `--tests` 过滤跑全量 `test` 会在本机挂起（>2h 无进展），属既有环境问题，不能作为验证证据。**
- **代码侧验证（P1）**：`./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.Nnhanman*"` → `NnhanmanParserTest` 12/12 通过（4 个离线 fixture：list / search / detail / chapter），`NnhanmanIntegrationTest` 2 个用例按 `NNHANMAN_INTEGRATION_TEST=1` 默认跳过（本机 JVM 无代理，线上用例跑不了）。再跑 `--tests "org.skepsun.kototoro.parsers.site.zh.*"` → **441 个用例、7 失败、67 跳过**，失败集与 P0 基线**完全相同的 4 个类**（CopyContentAuth 2 / CopyContentImageLoad 1 / CopyContentXigelide 2 / WnacgRead 2，均为直连超时），新增的 12 个用例全部通过。KSP 已生成 `ContentParserSource.NNHANMAN` 与工厂分支，`.github/summary.yaml` 的 `total` 由 115 变为 116。
- **代码侧验证（P2）**：`./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.BoyloveParserTest"` → **16/16 通过**（6 个离线 fixture：list / search / search_empty / detail / detail_vip / chapter；其中 `detail_vip.html` 是在真实详情页基础上把一话标成 `isvip="1"` 的派生样本，用于验证付费话被剔除且编号不错位，非抓包原文）；`--tests "org.skepsun.kototoro.parsers.site.zh.Boylove*"` → `BoyloveIntegrationTest` 2 个用例按 `BOYLOVE_INTEGRATION_TEST=1` 默认跳过。再跑 `--tests "org.skepsun.kototoro.parsers.site.zh.*"` → **461 个用例、7 失败、71 跳过**，失败集仍是与 P0/P1 基线**完全相同的 4 个类**（CopyContentAuth 2 / CopyContentImageLoad 1 / CopyContentXigelide 2 / WnacgRead 2，直连超时），新增的 16 个用例全部通过。KSP 已生成 `ContentParserSource.BOYLOVE`（`ContentType.HENTAI_MANGA`）与工厂分支，`.github/summary.yaml` 的 `total` 由 116 变为 117。
- **代码侧验证（P3）**：`./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.Manwa2*"` → `Manwa2ParserTest` **16/16 通过**（6 个离线 fixture：list / ranking / search / detail / detail_many / chapter；`chapter.html` 只保留真实阅读页里那段加密 `var params` 与空容器，`detail_many.html` 保留 8 话长目录样本，其余为对应页面裁切后的最小结构），`Manwa2IntegrationTest` 2 个用例按 `MANWA2_INTEGRATION_TEST=1` 默认跳过（本机 JVM 无代理）。再跑 `--tests "org.skepsun.kototoro.parsers.site.zh.*"` → **479 个用例、7 失败、73 跳过**，失败集仍是与 P0/P1/P2 基线**完全相同的 4 个类**（CopyContentAuth 2 / CopyContentImageLoad 1 / CopyContentXigelide 2 / WnacgRead 2，直连超时），新增的 16 个用例全部通过、2 个在线用例按预期跳过。KSP 已生成 `ContentParserSource.MANWA2`（`ContentType.HENTAI_MANGA`）与工厂分支，`.github/summary.yaml` 的 `total` 由 117 变为 118。
- **限制**：
  1. 直连通路受本环境网络白名单限制（仅 github 等少数域），因此"直连 000"不能推断国内不可达，**上架前需国内网络 + 真机复核**。
  2. 章节全表已对 nnhanman 做多作品回归（336 / 70 / 64 / 7 话四例），阅读页防盗链与图片 Referer 校验仍只做单样本抽查。
  3. 韩漫站点域名漂移极快（`jjmhw2.top` / `www.jjmhw12.top` / `ikanmh.xyz` 同源不同域即为例证），实现时域名必须走 `configKeyDomain` 可配置，不得硬编码。
  4. 多个候选为成人向（nnhanman「无遮挡无删减」、boylove、manwa），需连同 `ContentRating.ADULT` / `ContentType.HENTAI_MANGA` 标记一起评估。
  5. 站群域名的存活期通常只有数月，`configKeyDomain` 预设不等于永久可用；建议在来源说明里提示用户可自行改域名。
  6. boylove 的 `lastPage` 是布尔值，翻页只能靠"返回条数 < 页大小"收敛；若站点日后改成真实页码，`hasNextPage` 判定需复核。`isvip=="1"` 的付费话一律剔除，因此全付费作品会出现在列表里但章节为空——这是按仓库"不绕过付费内容"约定做的取舍，不是解析缺陷。
  7. manwa2 的阅读参数协议（AES 密钥、`host` 校验）与图片防盗链（必须带站点根域 Referer）已实测，但**图片 URL 形态（图床绝对地址 + 明文 webp 响应）只对 4 个作品抽样验证**；若站点把 `source_id=12` 切回相对路径的加密响应，需要给页面补 `BaipiaoguaiImageDecoder` 的解密 Interceptor（同族 `DdManhuaParser` 已有实现，可直接接）。另外该站 `/custom/*` 与 `/search` 都只有固定 30 条，翻页与筛选必须走 `/category`，这是站点限制而非解析遗漏。

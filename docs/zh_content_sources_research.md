# 中文内容源调研：漫画 / 小说 / 视频（2026-10-28）

目标：在仓库现有 95 个已注册源之外，找出「好用且尚未纳入」的中文漫画、小说、视频站点，并给出可执行的实施优先级。

上一份 `docs/chinese_manga_sources_research.md`（2026-08-03）只覆盖漫画，且当时的候选（图库/滴答/古风/飞飞/漫画100）均已落地，本文档是它的续篇并扩展到小说与视频。

## 结论速览

| # | 源 | 类型 | 为什么值得做 | 证据等级 | 难度 |
|---|---|---|---|---|---:|
| 1 | 腾讯动漫（ac.qq.com） | 漫画 | 官方免费平台，`一人之下` 单作品 859 章可枚举，目录/详情全 SSR | ✅ 一手实测 | 中高 |
| 2 | 快看漫画（kuaikanmanhua.com） | 漫画 | 国内最大原创条漫，有匿名 API | 🔎 上游实现佐证 | 中 |
| 3 | 咚漫 Webtoon 中文（dongmanmanhua.cn） | 漫画 | 韩国 Webtoon 官方中文版，列表页完整 SSR | ✅ 一手（阅读页待复核） | 中 |
| 4 | 落秋中文（luoqiuzw.com） | 小说 | 99 章/作品 SSR，正文以 base64 内嵌在 HTML 里 | ✅ 一手实测 | 低 |
| 5 | 樱花动漫（yhdmz.org） | 视频 | 国内访问量最大的免费动画站，m3u8 直链 | ✅ 域名存活 / 结构待探 | 中 |
| 6 | 六漫画（www.liumanhua.com） | 漫画 | 与滴答漫画**同库同协议**，已合并进 `DDMANHUA` 源作为第二域名 | ✅ 一手实测（同 ID 同作品） | 已完成 |
| 7 | 番茄小说（fanqienovel.com） | 小说 | 体量最大的免费网文平台 | ✅ 一手实测 | 高（正文走接口 + 字体混淆） |
| 8 | 全本小说（quanben.io）/ 52书库 | 小说 | 免费聚合，列表/详情结构简单 | ✅ 一手实测 | 低 |

### 已落地（2026-10-29）

- **滴答漫画 + 六漫画合并为单一源**：`DdManhuaParser` 支持 `ddmanhua.com` 与 `www.liumanhua.com` 两个预设域名；作品/章节 URL 规范化为 `/book/{id}`、`/book/{bookId}/{chapterId}`，切换镜像后已收藏条目仍可打开；两站搜索均返回空结果，维持只做分类浏览。

明确**不建议**：正版视频平台（B站/腾讯视频/爱奇艺/优酷：付费 + DRM）、苹果CMS 盗版资源 API（版权风险）、七猫小说（Aliyun WAF JS 挑战）。

## 方法与取证边界

- 环境：本机 Clash 代理 `127.0.0.1:7890`，出口 `89.125.70.102`（新加坡，AS26383 Baxet Group）。
- 时间：2026-10-28；工具：`python3 requests`（20 s 超时）与 `curl -sSL --compressed`（30 s），Chrome 131 UA，`Accept-Language: zh-CN`。
- 采集口径：HTTP 状态、最终 URL、响应体积、`<title>`、SSR 作品/章节/图片链接计数、SPA 标记（`__NUXT__` / `__NEXT_DATA__` / `__INITIAL_STATE__`）、Cloudflare 挑战标记。
- **关键限制**：出口在境外，国内站大面积「25–30 s 无响应（HTTP 000）」属于境外 IP 被墙或被站方拒绝，**不等于站点已死**。因此域名是否现役，另用两份 2026-10 仍在更新的上游数据交叉核对：
  - Keiyoushi `extensions-source` 的 `src/zh`（59 个中文源，仓库当前分支 `main`）；
  - Legado 书源合集 `aoaostar/legado`（`release` 分支，2026-10-28 有推送，样本 108 条书源）。
- 覆盖核对：仓库 `summary.yaml` 为 `total: 95`，其中 `zh` 源 49 个；Keiyoushi 的 59 个 zh 源里，本仓库已覆盖 15 个（漫画柜、Komiic、包子漫画（`baozimanhua` / `baozimhorg` 两个入口）、再漫画、哔咔、紳士漫畫、禁漫天堂、18漫画、Hanime1、CCC追漫台、漫小肆、优酷漫画、H漫画、漫画1234）。

## 一、漫画候选

### A. 官方 / 大型平台（差异化最高）

| 候选 | 现役域名 | 实测（2026-10-28） | 技术形态 | 结论 |
|---|---|---|---|---|
| 腾讯动漫 | `ac.qq.com` | 列表 200 / 106 KB，`/Comic/comicInfo/id/{id}` 12 条；详情 719 KB，859 个 `/ComicView/index/id/{comicId}/cid/{cid}` | 列表/详情全 SSR；阅读页（桌面 615 KB、移动 101 KB）都**不直接给图**，正文在 `var DATA = '<base64>'` 里，实测该串被周期性注入噪声字符（按 12 字符步长剔除可还原出 `{"comic":…}` 骨架，但位置并非严格等距）；解出后为 JSON：`chapter.canRead`、`picture[].url` | **首选**，但阅读页要复刻解码规则；付费章 `canRead=false` 必须显式抛异常，不绕过 |
| 快看漫画 | `kuaikanmanhua.com` | 首页 200 / 227 KB，含 `__NUXT__` 载荷；`api.kkmh.com` 根路径可达（`/v1/search/comic` 返回 `{"code":200}` 但无数据，`/v2/pweb/...` 404） | 前端 Nuxt；真正取数需 `api.kkmh.com` 的完整参数/签名，Keiyoushi 用 `/web/topic/{id}` 与 `/webs/comic-next/{id}` | 次选，需抓包复刻请求头与签名 |
| 咚漫 Webtoon 中文 | `dongmanmanhua.cn` | 首页 232 KB；`/{GENRE}/{slug}/list?title_no=NNNN` 详情 118 KB、11 个 viewer 链接 | 列表/详情 SSR（title_no 为稳定 ID）；viewer 页返回 2.9 KB「Page not found」 | 候选。阅读页路径需真机浏览器确认（可能依赖 referer/secureKey） |
| 哔哩哔哩漫画 | `manga.bilibili.com` | 200 / 534 KB | 需登录态，内容以付费/券为主 | 暂缓（合规边界） |
| 顶通 Toptoon 国际中文 | `toptoon.net` | 200 / 71 KB，`href="{{link}}"` 模板占位符 | 前端模板渲染 + 接口；Keiyoushi 已有实现可参考 | 候选，难度中高 |

### B. 老牌聚合站（内容量大、解析要解包）

| 候选 | 现役域名 | 实测 | 技术形态 | 结论 |
|---|---|---|---|---|
| 动漫屋 DM5 | `dm5.com` | 列表/搜索 200（59–435 KB），但作品链接由脚本注入 | 作品页 `/manhua-{id}/`、章节 `/m{chapterId}/`；图片混淆，Keiyoushi 依赖 `lib/unpacker` | 价值高（老牌全量库），成本也高 |
| 漫画人 | `manhuaren.com` | 首页 200 但仅 12 KB（应用壳） | 移动 API：`/v1/manga/getDetail?mangaId=`、`/v1/manga/getRead?mangaSectionId=` | 候选，需抓包确认 headers |
| MangaBZ | `mangabz.com` | 200 / 46 KB（Cloudflare 代理） | 列表 `/manga-list-p{n}/`、搜索 `/search?keyword=`；阅读页图片为懒加载 DOM，站点前置 CF，需 cookie | 中低（反爬成本高） |
| 8comic 無限動漫 | `8comic.com` | 200 / 193 KB | 阅读页图片需 JS 解包（上游实现内置 `j.js` 核心函数 + 简繁转换） | 中低 |
| 漫客栈 | `mkzhan.com` | 200 / 114 KB | SSR，但作品标 `data-price` / `data-is_vip` | 只能解析公开免费章，优先级低 |
| 漫画台 / 看漫画 / 99770 | `manhuatai.com` 404、`manmankan.com` 200 / 16 KB、`99770.cc` 超时 | — | 首页多为聚合导航 | 排除 |

### C. 小聚合站（逐个实测：各自独立，不是同一套 CMS）

最初按首页特征把它们归为「同族 CMS」，2026-10-29 逐个抓列表/详情/阅读页后证伪——**路由、DOM、协议、作品 ID 空间都不同**，用同一批 ID 跨站查询返回的作品也各不相同：

| 站点 | 现役域名 | 详情路由 | 实测结论 |
|---|---|---|---|
| 瓜子漫画 | `guazimanhua.com` | `/comic.php?id=…` | PHP 自建，章节 `chapter.php?id=…`，无 AES params |
| 漫画社 | `www.311s.com` | `/comic_{id}.html` | 自建模板，无 AES params |
| 喜漫 | `www.favcomic.com` | `/comic/detail/{雪花ID}` | 独立 ID 体系，章节 `/comic/chapter/{id}` |
| 漫画160 | `www.mh160mh.com` | `/kanmanhua/{slug}/` | slug 路由，无数字 ID |
| 喵趣 | `www.miaoqumh.org` | `/{slug}_{id}` | 同 ID 与六漫画不是同一作品，数据库不同 |
| 如漫画 / 读漫画屋 | `m.rumanhua2.com` / `m.dumanwu1.com` | 随机 slug（`/OjuAxci/`） | 两站共用同一模板（`all.css?v=17120008`）；**读漫画屋详情页实为 37 MB 的 APK 下载页**，不可作阅读源 |
| 漫画屋 | `www.mhua5.com` | — | 回 522（Cloudflare 回源失败） |

唯一确认同库同协议的是**滴答漫画 ↔ 六漫画**：book id `618076` 在两站都是「神奇蜘蛛侠v1」，章节 id 同为 `224176`（`/chapter/618076-224176.html` vs `/618076/224176.html`），阅读页 `params` 共用 key `9S8$vJnU2ANeSRoF`、`source_id=6`、图片数一致；列表/排序/分页路由也完全相同（`/category/list/{n}/order/{hits|addtime|score}/page/{p}`）。已按此合并为单一源。

### D. 排除项

- 36漫画/漫画台类「官网只有 APK 下载页」的站：无 Web 阅读链。
- 漫画DB `manhuadb.com`、风之动漫 `fzdm.com`、HentaiClub：境外出口 TLS/超时不可达，需国内网络复核后再定。
- 光凪 `hikarinagi.org`、JComic `jcomic.net`：社区/资讯向，不是稳定阅读源。

## 二、小说候选

### A. 大型免费平台

| 候选 | 现役域名 | 实测 | 关键结论 |
|---|---|---|---|
| 番茄小说 | `fanqienovel.com` | 首页/详情 200 / 196 KB，`/page/{bookId}` 到 `/reader/{chapterId}` 共 351 个章节链接 | 详情页 SSR 完整；**阅读页只 SSR 出 213 字（该章 2701 字）**，且页面内存在 `font-face`/`<svg>` → 正文走接口 + 字体混淆。Legado 书源使用的接口域为 `api5-normal-lf.fqnovel.com`。高价值、高难度 |
| 七猫小说 | `qimao.com` | 首页 15 KB，含 `aliyun_waf_aa` / `aliyun_waf_bb` meta（Aliyun WAF 下发挑战）；`api-bc.wtzw.com/api/v1/book/detail?book_id=1` 返回 404（该路径不是有效接口） | 站点前置 Aliyun WAF，接口路径需重新确认，**暂缓** |
| 刺猬猫 | `ciweimao.com` | 首页 130 KB；`/book/100000001` 37 KB 后 302 到登录页 | 付费/登录混合，只能取公开免费章，优先级中低 |
| 纵横 / 17K / 塔读 / 小说阅读网 | `zongheng.com`(SPA 407 KB)、`17k.com`(220 KB)、`tadu.com`(44 KB)、`readnovel.com`(157 KB) | 均存活 | 正版付费为主，可解析免费试读章；合规清晰但内容可用量少 |
| 起点 / 晋江 | `qidian.com` 返回 202 挑战页；`jjwxc.net` 200 但 GBK | — | 重度反爬 + 付费，不建议 |

### B. 免费聚合站（结构简单，落地快）

| 候选 | 现役域名 | 实测 | 结论 |
|---|---|---|---|
| 落秋中文 | `luoqiuzw.com` | 详情 18.9 KB / 99 章（`/book/{id}/{chapterId}.html`） | 正文是 `document.writeln(qsbs.bb('<base64>'))`，base64 解出 `<p>` 段落（已验证：`PHA+5pma5LiK5Lmd54K544CCPC9wPg==` → `<p>晚上九点。</p>`）。**难度低**，不执行 JS 也能取全文 |
| 全本小说 | `quanben.io` | 200 / 40 KB，作品路径 `/n/{slug}/` | 结构简单，可做 |
| 52书库 | `52shuku.net` | 200 / 25 KB，分类 `/wuxia/`、`/KeHuan/` 等 | 结构简单，可做 |
| 101言情 | `txtxs101.com`（`txt101.com` 已跳转） | 200 / 33 KB | 可做，偏言情向 |
| 随心看 / 格格党 | `m.suixkan.com` 200 / 73 KB；`51ggd.com` 200 / 4 KB | 存活 | 待结构复核 |
| 69书吧 / 和图书 / 笔趣阁系（ibiquge、xsbiquge、gashuw、77shuku、75zhongwen、4shuwang、ruochenwenxue、pi12345、cpwenku、2kxs、damixs、zwduxs、baoshus、yeban360、23us） | — | 全部 403（Cloudflare 挑战）或境外出口 25–30 s 无响应 | **不在本环境定论**：这批是 Legado 书源里的现役域名，需国内网络/真机复核后再排期 |

## 三、视频候选

先说结论：**中文免费影视站基本是 SPA 或混淆聚合，投入产出比最差**；正版平台是付费 + DRM，属于不可纳入范围。建议视频方向只做「动漫向」1–2 个，不做影视聚合。

| 候选 | 现役域名 | 实测 | 结论 |
|---|---|---|---|
| 樱花动漫 | `www.yhdmz.org`（200 / 43.6 KB；EasyBangumi 扩展用 `m.yhdmz.org`） | 域名存活，列表结构待探 | 动漫向首选：国内最具知名度的免费动画站，播放为 m3u8 |
| 动漫456 | `dm456.com` | 200 / 29.4 KB | 备选动漫站，SSR |
| 风车动漫 | `fengchedm.com` → `fcbbcc.com` | 200 / 22.9 KB | 备选动漫站（域名跳转需处理） |
| 爱剧迷 Gimy | `aigm.tv` | 首页 200 / 160 KB SSR，播放页 `/play/{混淆 token}` | 影视聚合，token 需逆向，难度高 |
| 爱看机器人 | `ikanbot.com` | 200 / 27 KB，`/play/{id}` | 播放指向第三方 iframe，可用性差 |
| 低端影视 | `ddys.app`（`ddys.io` 被 CF 403、`ddys.pro` 超时） | 200 / 72–128 KB | 更像「资源索引」（磁力/网盘），不适合逐页模型 |
| 欧乐影院 | `olevod.com` | 首页 7.5 KB；`/index.php/vod/show/id/1.html` 返回同一 SPA 壳 | 客户端路由，需接口逆向 |
| 蜜柑计划 / 动漫花园 | `mikanani.me` / `share.dmhy.org` | 200 / 184 KB、349 KB | BT 种子源，不符合「章节=页面」模型，只能做元数据 |
| 苹果CMS 资源 API | `api.ffzyapi.com/api.php/provide/vod/?ac=list` | 200，`total: 98834`、`pagecount: 4942`，JSON 结构化 | 技术上最容易（一个通用解析器覆盖数万影视），但属盗版聚合，**明确不建议内置** |
| 正版平台 | bilibili / 腾讯视频 / 爱奇艺 / 优酷 / 芒果 | — | 付费 + DRM，不绕过，排除 |

## 四、成人向（单独列，与现有 `HENTAI_*` 源一致的取舍）

| 候选 | 现役域名 | 实测 | 备注 |
|---|---|---|---|
| 香香腐宅 BoyLove | `boylove.cc` | 200 / 258 KB SSR | 上游仍指向已 404 的 `fuhouse.info`，说明其域名已迁移；BL 向，当前无覆盖 |
| 鸟鸟韩漫 | `nnhanman.xyz` | 200 / 44 KB | 韩漫，无覆盖 |
| 92韩漫（漫小肆系） | `ikanmh.xyz` 200 / 60 KB、`jjmhw2.top` 200 / 60 KB | 同一套模板 | 与已实现的 `MXS_`（漫小肆）重复度高 |
| 嬉皮漫画 | `m.hipmh.com` | 200 / 126 KB | 上游实现存在 |
| Hanman18 | `hanman18.com` | 200 / 228 KB | 内容出自禁漫系，重复度高 |
| 卡通18 | `cartoon18.com` | 200 / 46 KB，Cloudflare | 反爬中等 |
| 肉漫屋 | `rou.pub` | 200 / 18 KB（广告跳板） | 非内容站 |
| 漫蛙 | `manwa.me` | 403 Cloudflare 挑战 | 需 CF cookie，暂缓 |
| 维护提醒（非新增） | 禁漫 `18comic.vip` 返回 403 CF；Hanime1 现域 `hanimeone.me` 200 | — | 仓库 `JMCOMIC` / `HANIME1` 需关注入口漂移 |

## 五、建议实施顺序

1. **腾讯动漫**：`ac.qq.com`。先做列表（`/Comic/all/`，12 条/页）→ 详情（859 章，SSR 可直接解析）→ 阅读页 `var DATA` 解码。要点：base64 JSON 里混入周期性噪声字符，需要复刻站方剔除规则（否则改用章节接口）；`chapter.canRead=false` 显式抛「付费章节」异常。
2. **快看漫画**：复刻 `api.kkmh.com` 列表/详情，图片页走 `/webs/comic-next/{id}`。要点：设备头/签名为主要不确定性，先用真实请求验证匿名可用。
3. **咚漫**：`/{GENRE}/{slug}/list?title_no=NNNN` 完全 SSR，`title_no` 是稳定 ID（与域名无关，满足 UID 约定）。阅读页需真机确认。
4. **落秋中文**（小说，成本最低）：详情 `/book/{id}/` → 章节 `/book/{id}/{chapterId}.html`，正文把 `qsbs.bb('...')` 的 base64 解码即可，无需执行 JS。
5. **樱花动漫**：先确认列表/详情/播放三段结构，播放页取 m3u8；一集映射为一个 chapter。
6. ~~同族 CMS 批量~~：已证伪，这批站各自独立，不能共用一套实现；其中六漫画已并入滴答源。若要扩源，需按站单独写（成本与常规新站相同），优先级低。
7. **番茄小说**：价值最高也最难。形态是「详情 SSR + 正文接口 + 字体反混淆」，需要在真机抓包后实现，且要评估平台条款风险。

暂缓/不做：七猫（WAF）、DM5、MangaBZ、8comic（都已证明可行但需要 JS 解包/unpacker，成本高于收益）、欧乐/爱剧迷（token 逆向）、瓜子/漫画社/喜漫/漫画160/喵趣/如漫画/读漫画屋（各自独立且体量小，含 APK 跳板站）、正版视频平台。

## 六、合规与风险边界

- 不绕过登录、付费、验证码：官方平台只解析公开免费章节，遇到付费标记显式抛异常（腾讯 `canRead`、漫客栈 `data-is_vip` 是现成的判定字段）。
- 聚合站（落秋、全本、52书库、笔趣阁系）内容多为盗版转载；苹果CMS 资源 API 明确不建议内置。是否内置由项目方决定，本文档只提供技术结论。
- 域名漂移频繁，本次实测已确认：`fuhouse.info` → `boylove.cc`、`ddys.io` → `ddys.app`、`yhdm.tv` → `yhdmz.org`、`fengchedm.com` → `fcbbcc.com`、`txt101.com` → `txtxs101.com`；另有历史记录 `shencou.com` → `wowenku.com`（见 `Shencou.kt` 注释）。仓库内 `DMBUS`（`dmbus.cc`）本次实测不可达，需复核。
- 境外出口不可达 ≠ 站点下线，上架前必须在国内网络 + 真机复核。

## 七、待复核清单（本次未能一手确认）

- 咚漫阅读页真实路径（viewer 返回 404）。
- 顶通 `toptoon.net` 的接口与鉴权。
- 快看 `api.kkmh.com`、漫画人 `/v1/manga/*` 是否仍允许匿名请求。
- 樱花动漫列表/详情/播放页选择器。
- 爱剧迷 `/play/{token}` 生成规则。
- 15 个笔趣阁/69书吧系站的国内可达性与结构（本环境全部 403 或无响应）。

## 参考数据源

- Keiyoushi `extensions-source`：`https://github.com/keiyoushi/extensions-source`（`src/zh`，59 个中文源，2026-10 检出）
- EasyBangumi 樱花动漫扩展：`https://github.com/easybangumiorg/EasyBangumi-Extension-yhdmp`
- Legado 书源合集：`https://github.com/aoaostar/legado`（`release` 分支 `sources/*.json`）

## 手动复核命令

```shell
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

# 腾讯动漫：列表 → 详情章节数 → 阅读页 DATA
curl -sSL -A "$UA" https://ac.qq.com/Comic/all/ | grep -c 'comicInfo/id/'
curl -sSL -A "$UA" https://ac.qq.com/Comic/comicInfo/id/531490 | grep -c 'ComicView/index/id/'
curl -sSL -A "$UA" https://ac.qq.com/ComicView/index/id/531490/cid/2 | grep -o "var DATA = '.\{0,80\}"

# 落秋中文：正文是 base64，可直接解
curl -sSL -A "$UA" https://www.luoqiuzw.com/book/8021/ | grep -oE '/book/8021/[0-9]+\.html' | head -1

# 樱花动漫 / 动漫456 / 风车动漫
curl -sSIL -A "$UA" https://www.yhdmz.org/ | head -1
curl -sSL -A "$UA" https://www.dm456.com/ | grep -oE '<title>[^<]*'

# 苹果CMS 资源 API（仅作技术验证，不建议内置）
curl -sSL "https://api.ffzyapi.com/api.php/provide/vod/?ac=list" | head -c 200
```

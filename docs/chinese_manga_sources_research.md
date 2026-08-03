# 中文漫画源调研（2026-08-03）

## 覆盖核对

- 当前 Kototoro parser、Kototoro 主应用以及本地第三方扩展索引中均未发现 `ddmanhua`、`tuku.cc` 或 `manhua100`。
- Keiyoushi `extensions-source` 最新检出提交 `d0ead2f7d5ffd3c66ae31e01a31d9f65c4cb8407` 中未发现三个站点的实现、域名或别名。
- Keiyoushi 当前发布索引 `index.min.json` 未包含三个站点；其公开 issue/PR 搜索也没有相应域名记录。
- 当前仓库的完整本地 Git 历史未发现三个域名或中文名，未发现曾加入后移除的实现。
- Kototoro 使用原生 `ContentParser`，不直接加载 Mihon/Keiyoushi、Legado 或其他项目的 parser。站点 parser 由 `@ContentSourceParser` 注解和 KSP 注册。
- 第二轮候选使用 Keiyoushi `extensions-source` 提交 `a101aafbb40235b7c604f437e53984189bd757c9` 做精确域名及中文名核对；`gfmh.app`、`feifeimh.cc`、`tuhaom.com`、`36manhua.cn`、`mkzhan.com`、`vol.moe`/`kzo.moe` 均无命中。Kototoro 当前树及完整本地 Git 历史也没有这些域名。

## 已有实现模式与共用边界

- 图库漫画：服务端渲染 HTML + `data-original` 懒加载图片；图片需要章节页 Referer。
- 漫画100：服务端渲染列表/详情 + AES-CBC 阅读参数 + Base64 图片代理。
- 滴答漫画：服务端渲染列表/详情 + AES-CBC 阅读参数；部分 `source_id=12` 图片还需二次 AES-CBC 解密。
- 稳定共用能力仅保留两层：`AesCbcDecoder` 处理“16 字节 IV 前缀 + AES-CBC”载荷；`BaipiaoguaiImageDecoder` 处理多个站点共同使用的加密图片后端。站点 URL、HTML 选择器、筛选和章节规则保持独立，避免建立脆弱的 CMS 大基类。

## 图库漫画

- 真实域名：`https://www.tuku.cc`，裸域名和 HTTP 均跳转到该地址。
- 列表：`/comics/`、`/comics-p2/`；默认路径按人气排序，`order2` 为更新时间，`order18` 为新品上架；`/update/` 当前返回空数据。
- 筛选：支持题材、地区和连载状态的组合路径，例如 `/comics-region2-tag1-status1-order2-p2/` 表示日本、热血、连载中、按更新时间排序的第 2 页。
- 搜索：GET `/search?title={关键词}&page={页码}`，三个测试关键词均返回服务端 HTML；“一人之下”的模糊搜索没有把同名作品排在结果中。
- 详情：`/manga-{id}/`，服务端 HTML 包含标题、作者、状态、题材、简介、封面和完整章节目录。
- 元数据：实现会从 keywords 元数据提取站点提供的第二标题，并从列表卡片和详情页保留作者、题材、连载状态及成人内容等级。
- 章节：`/chapter{id}/`，章节列表保留站点原始顺序，包含话、卷、番外和无编号标题。
- 图片：阅读页 `img[data-original]` 一次给出完整列表和带 `cid/key/type` 的 CDN URL。测试章节 `/chapter739158/` 标注 46P，解析到 46 张。
- 请求头：图片不带 Referer 返回 403；带站点根页、详情页或章节页 Referer 返回 200。无需 Cookie，使用章节页 Referer 最小且准确。
- 防护：HTML 无 JavaScript 执行要求；主页由 Cloudflare 代理，但普通请求可直接访问。

## 滴答漫画

- 真实入口为 `http://ddmanhua.com`；HTTPS 会 301 降级到 HTTP，因此解析器显式使用 HTTP 基址。
- 分类：`/category/list/{地区}`，地区编号 1 至 4 分别是国产、日本、韩国和欧美；每页 30 部，分页路径为 `/page/{页码}`。
- 排序：`order/hits`、`order/addtime`、`order/score` 分别对应人气、更新时间和评分。
- 搜索：GET `/index.php/search?key={关键词}` 和 `/search/{关键词}` 当前对站内确实存在的标题仍返回空结果，因此实现中主动关闭搜索能力，不扫描分类页伪造搜索。
- 详情：`/book/{id}.html`，服务端 HTML 包含标题、评分、作者、状态、类型、简介、封面和完整章节目录；失效作品仍返回 HTTP 200，但没有详情容器。
- 章节：`/chapter/{bookId}-{chapterId}.html`。站点目录包含大量不连续和无编号标题，实现保留原始 DOM 顺序，仅单独解析明确的“第 N 话”。
- 阅读参数：章节 HTML 中的 `params` 采用“前 16 字节 IV + AES-128-CBC/PKCS7 密文”，解密后读取 `host`、`source_id` 和 `images`；实现使用 JVM 原生密码库，不执行站点 JavaScript。
- 普通图片线路：当前在线样本 `source_id=6` 直接返回 JPEG，无需 Referer。样本 `/chapter/618076-224176.html` 解析到 22 张图片。
- 加密图片线路：脚本中的 `source_id=12` 会下载加密二进制并再次进行 AES-CBC 解密。实现通过带内部 URL fragment 的精确拦截器替换响应体，离线密码夹具已通过；当前尚未找到在线的 `source_id=12` 章节，不能视为线上验证完成。

## 漫画100

- 真实域名：`https://www.manhua100.com`，裸域名和 HTTP 均跳转到该地址。
- 列表：`/category/{筛选}/{排序}/page/{页码}`，每页 35 部；支持地区、题材、进度组合筛选，并支持热门人气和更新时间排序。
- 搜索：GET `/search?q={关键词}&page={页码}`，每页 30 部。表单虽提供动态 `__searchtoken__`，但 GET 搜索无需携带该字段。
- 详情：`/{mangaId}`，标题、作者、状态、题材、简介、封面及完整章节目录均直接位于 HTML；章节路径为 `/{mangaId}/{chapterId}.html`。
- 图片：章节 HTML 包含加密的 `params` 字符串。当前算法为 Base64 解码后取前 16 字节作为 IV，以固定 16 字节密钥进行 AES-128-CBC/PKCS7 解密；实现使用 JVM 原生密码库，不执行站点 JavaScript。
- 图片地址：解密后读取 `chapter_images`、`images_domain` 和 `images_base64`。当前图片代理要求章节页 Referer；无 Referer 返回 403，携带 Referer 返回 200。
- 站点使用通用 CMS，但尚未验证足够多同 CMS 站点，因此当前保持单站点实现，不提前抽取 multisrc。

## 古风漫画

- 真实入口：`https://www.gfmh.app`；裸域名可访问移动模板，解析器固定桌面子域名以保持 HTML 结构稳定。
- 分类：`/category`，每页 16 部；地区为 `/category/list/1` 至 `/category/list/4`，分页后缀为 `/page/{页码}`。地区和完结状态组合路径会返回空列表，因此没有宣称支持组合筛选。
- 排序：分类页按更新时间输出；站点首页虽有热门区块，但不存在可分页的热门列表，因此只开放 `UPDATED`。
- 搜索：表单指向 `/index.php/search?key={关键词}`，规范路径为 `/search/{关键词}`；两种路径对站内已存在作品仍返回 0 条，解析器主动关闭搜索。
- 详情：`/{bookId}.html`，服务端 HTML 包含标题、作者、题材、状态、简介、封面和完整章节目录。
- 章节：`/{bookId}/{chapterId}.html`；完整目录按正序输出，实现保留原始顺序并识别“第 N 话”和 `Act.N`。
- 阅读参数：与滴答漫画相同，使用“16 字节 IV + AES-128-CBC/PKCS7 密文”，解密后校验 `host` 并读取 `source_id`、`images`，不执行混淆 JavaScript。
- 图片：当前线上样本为 `source_id=10` 的 HTTPS 直链；首图和末图均成功下载。`source_id=12` 的 Baipiaoguai 二次 AES 解密由共用离线密码夹具覆盖，但没有把它记作古风线上验证。

## 飞飞漫画

- 真实入口：`https://www.feifeimh.cc`；列表 `/booklist` 每页 28 部，分页参数为 `page`。
- 筛选：`cate`、`area`、`end` 分别表示题材、地区和进度，可组合使用；进度值 2 为连载、1 为完结。
- 搜索：GET `/2cb?keyword={关键词}&sn=pp`，线上搜索“斗破苍穹”能稳定返回 `/book/12992`。
- 详情：`/book/{id}`，服务端 HTML 包含标题、别名、作者、状态、地区、标签、简介和封面。
- 目录：详情页章节 ID 和标题由前端 RC4 脚本混淆，但“开始阅读”链接是明文；阅读页侧栏直接输出该作品完整明文章节目录。解析器采用“详情页首章链接 → 阅读页 SSR 侧栏”的稳定链路，不执行 `eval`，也不移植混淆器。
- 图片：`/chapter/{id}` 的 `.comiclist .imgpic img` 直接包含完整 WebP 列表。懒加载节点使用 `data-original`，已加载节点的 `data-original` 是统一占位图、真实地址在绝对 `src`，实现显式区分两种情况。
- 请求头：图片 CDN 不要求 Referer；无 Referer、根页 Referer 和章节页 Referer 均返回 HTTP 200。实现只附加桌面 User-Agent。

## 第二轮候选筛选

| 候选 | 在线结构与访问结果 | 覆盖结果 | 风险/结论 | 优先级 |
|---|---|---|---|---:|
| 古风漫画 | 完整 SSR 列表、详情、章节；AES 阅读参数，图片可直接访问 | Keiyoushi/Kototoro 当前与历史均无 | 搜索失效，但分类阅读链稳定；已实现 | 1 |
| 飞飞漫画 | 完整 SSR 列表、搜索、详情和阅读页图片；目录可从阅读页明文侧栏取得 | Keiyoushi/Kototoro 无 | 无需执行 RC4/`eval`；已实现 | 2 |
| 土豪漫画 | 移动端详情和目录为 SSR；每张图对应 `/cartoonN/{chapter}-{page}.html` 独立页面 | Keiyoushi/Kototoro 无 | 单章会放大为数十次 HTML 请求，桌面端多次超时且移动端末页实测 502；暂不实现 | 观察 |
| 漫客栈公开免费部分 | 官方 SSR 详情及完整目录；作品标记 `data-price`/`data-is_vip`，大量章节有 VIP 标签 | Keiyoushi/Kototoro 无 | 只能解析公开免费章节，不能绕过登录、付费或授权；需先筛选长期免费样本 | 4 |
| 36漫画 | 官网是 APK 下载宣传静态页，点击漫画仅弹出“APP 中实现” | Keiyoushi/Kototoro 无 | 没有 Web 列表、详情和阅读链，不适合 Web parser | 排除 |
| KMOX / Vol.moe | `vol.moe` 已迁移至 `kzo.moe`；公开元数据丰富，内容是 Kindle/EPUB/MOBI/ZIP 档案 | Keiyoushi/Kototoro 无 | 下载、推送和在线阅读均与登录、额度或 VIP 流程耦合，不是逐页公开漫画源 | 排除 |

## 质量评分（1 最差，5 最佳）

| 源 | 可访问性 | 更新活跃度 | 内容规模 | 差异化 | 解析难度 | 防护强度 | 域名稳定性 | 重复度 | 维护成本 | 建议 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 图库漫画 | 5 | 4 | 5 | 4 | 5 | 4 | 4 | 3 | 4 | 立即实现 |
| 漫画100 | 5 | 4 | 5 | 3 | 3 | 3 | 4 | 4 | 3 | 已实现 |
| 滴答漫画 | 3 | 3 | 4 | 2 | 3 | 3 | 1 | 5 | 2 | 已实现（无搜索） |
| 古风漫画 | 5 | 4 | 5 | 3 | 4 | 3 | 3 | 5 | 3 | 已实现（无搜索） |
| 飞飞漫画 | 5 | 4 | 5 | 3 | 4 | 4 | 3 | 4 | 3 | 已实现 |

“解析难度”“防护强度”和“维护成本”列的高分表示更容易解析、防护更弱、维护成本更低；“重复度”高分表示与现有内容重复更多。

## 手动集成验证

线上验证不进入普通 CI。可手动运行：

```shell
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.TukuParserTest"
TUKU_INTEGRATION_TEST=1 ./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.TukuParserIntegrationTest"
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.Manhua100ParserTest"
MANHUA100_INTEGRATION_TEST=1 ./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.Manhua100ParserIntegrationTest"
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.DdManhuaParserTest"
DDMANHUA_INTEGRATION_TEST=1 ./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.DdManhuaParserIntegrationTest"
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.GufengManhuaParserTest"
GUFENGMANHUA_INTEGRATION_TEST=1 ./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.GufengManhuaParserIntegrationTest"
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.FeifeiManhuaParserTest"
FEIFEIMANHUA_INTEGRATION_TEST=1 ./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.FeifeiManhuaParserIntegrationTest"
```

当前线上集成样本：搜索“海贼王”；作品 `/manga-70923/`；首章 `/chapter768098/`；详情页解析到 1034 个章节，首章解析到 104 张图片；首图和末图均为 HTTP 200、`image/jpeg`。

漫画100 线上集成样本：搜索“一人之下”；作品 `/26459`；详情页解析到 811 个章节，首章解析到 17 张图片；首图和末图均为 HTTP 200、`image/webp`。

滴答漫画线上集成样本：更新分类每页 30 部；作品 `/book/618076.html`；首章 `/chapter/618076-224176.html` 解析到 22 张图片；首图和末图均为 HTTP 200、`image/jpeg`。

古风漫画线上集成样本：更新分类每页 16 部；作品 `/616485.html` 解析到 23 个章节；首章 `/616485/216802.html` 的 AES 参数解析出直链图片，首图和末图均为 HTTP 200 且响应类型为 `image/*`。

飞飞漫画线上集成样本：分类每页 28 部；搜索“斗破苍穹”命中作品 `/book/12992`；通过明文首章 `/chapter/745212` 的侧栏解析到 600 个以上章节，首章解析到 36 张图片；首图和末图均为 HTTP 200、`image/webp`。

# 中文漫画源调研（2026-08-03）

## 覆盖核对

- 当前 Kototoro parser、Kototoro 主应用以及本地第三方扩展索引中均未发现 `ddmanhua`、`tuku.cc` 或 `manhua100`。
- Keiyoushi `extensions-source` 最新检出提交 `d0ead2f7d5ffd3c66ae31e01a31d9f65c4cb8407` 中未发现三个站点的实现、域名或别名。
- Keiyoushi 当前发布索引 `index.min.json` 未包含三个站点；其公开 issue/PR 搜索也没有相应域名记录。
- 当前仓库的完整本地 Git 历史未发现三个域名或中文名，未发现曾加入后移除的实现。
- Kototoro 使用原生 `ContentParser`，不直接加载 Mihon/Keiyoushi、Legado 或其他项目的 parser。站点 parser 由 `@ContentSourceParser` 注解和 KSP 注册。

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

## 质量评分（1 最差，5 最佳）

| 源 | 可访问性 | 更新活跃度 | 内容规模 | 差异化 | 解析难度 | 防护强度 | 域名稳定性 | 重复度 | 维护成本 | 建议 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 图库漫画 | 5 | 4 | 5 | 4 | 5 | 4 | 4 | 3 | 4 | 立即实现 |
| 漫画100 | 5 | 4 | 5 | 3 | 3 | 3 | 4 | 4 | 3 | 已实现 |
| 滴答漫画 | 3 | 3 | 4 | 2 | 3 | 3 | 1 | 5 | 2 | 已实现（无搜索） |

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
```

当前线上集成样本：搜索“海贼王”；作品 `/manga-70923/`；首章 `/chapter768098/`；详情页解析到 1034 个章节，首章解析到 104 张图片；首图和末图均为 HTTP 200、`image/jpeg`。

漫画100 线上集成样本：搜索“一人之下”；作品 `/26459`；详情页解析到 811 个章节，首章解析到 17 张图片；首图和末图均为 HTTP 200、`image/webp`。

滴答漫画线上集成样本：更新分类每页 30 部；作品 `/book/618076.html`；首章 `/chapter/618076-224176.html` 解析到 22 张图片；首图和末图均为 HTTP 200、`image/jpeg`。

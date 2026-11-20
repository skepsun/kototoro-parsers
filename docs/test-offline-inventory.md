# 联网测试清点与离线化（2026-11-13）

## 一句话结论

`./gradlew test` 现在**完全离线可跑**：694 个用例、0 失败、0 错误，且新增了一道护栏——普通测试里任何真实出网都会当场抛错并指出正确做法，不再出现"CI 上莫名超时/偶发失败"。

## 判定口径：什么才算"联网测试"

静态扫描（找 `httpClient` / 疑似挂起调用）会大量误报，因为**同名方法在不同解析器里语义完全不同**：

- `getPages(chapter)` 对电子书源常常是纯本地映射（例如 `StandardEbooks.getPages` 就是 `listOf(ContentPage(...))`），
  `Erome.getPages` 只有 `images:` 分支才发请求，`media:` 分支直接返回。
- `getPageUrl(page)` 多数是纯字符串拼接（`page.url`）。
- `getFilterOptions()` 是代码里声明的常量，从不联网。

所以本清单**以实证为准**：把网络黑洞（`HTTPS_PROXY=http://127.0.0.1:1`）后跑全量，仍然失败/挂死的才是真联网。静态扫描只用来生成候选。

## 修复前后

| | 用例 | 失败 | 说明 |
|---|---|---|---|
| 2026-10-23（历史记录） | 281 | 32 | 全部为未门控联网用例 |
| 本次修复前 | 未跑完 | — | `ContentParserTest.pages` 在断网时**挂死**而非快速失败（jstack 线程栈停在 `runTest` + `ktor/okhttp` 等待），全量跑无法结束 |
| 本次修复后 | 694 | **0** | 138 个用例因 `*_INTEGRATION_TEST` 未设置而跳过（53 个 IntegrationTest 类）；其中 21 个是新增的夹具回放用例 |

修复前的"挂死"比"失败"更糟：失败能定位，挂死只能等 CI 超时。

## 清点结果：静态候选 21 个 → 12 个真联网已门控、9 个误报；实证另揪出 1 个

### A. 已改名 `*IntegrationTest` 并加环境变量门控（12 个）

| 原文件 | 新文件 | 类名 | 环境变量 |
|---|---|---|---|
| `ContentParserTest.kt` | `ContentParserIntegrationTest.kt` | `ContentParserIntegrationTest` | `CONTENT_PARSER_INTEGRATION_TEST` |
| `site/galleryadults/zh/WnacgReadFlowTest.kt` | `WnacgReadFlowIntegrationTest.kt` | 同名 | `WNACG_INTEGRATION_TEST` |
| `site/zh/CopyMangaImageLoadTest.kt` | `CopyMangaImageLoadIntegrationTest.kt` | `CopyContentImageLoadIntegrationTest` | `COPYMANGA_INTEGRATION_TEST` |
| `site/zh/CopyMangaXigelideTest.kt` | `CopyMangaXigelideIntegrationTest.kt` | `CopyContentXigelideIntegrationTest` | `COPYMANGA_INTEGRATION_TEST` |
| `site/zh/DmbusTest.kt` | `DmbusIntegrationTest.kt` | 同名 | `DMBUS_INTEGRATION_TEST` |
| `site/zh/PicacgDynamicTagTest.kt` | `PicacgDynamicTagIntegrationTest.kt` | 同名 | `PICACG_INTEGRATION_TEST` |
| `site/zh/WnacgReadTest.kt` | `WnacgReadIntegrationTest.kt` | 同名 | `WNACG_INTEGRATION_TEST` |
| `site/all/BuonduaTest.kt` | `BuonduaIntegrationTest.kt` | 同名 | `BUONDUA_INTEGRATION_TEST` |
| `site/all/KiutakuTest.kt` | `KiutakuIntegrationTest.kt` | 同名 | `KIUTAKU_INTEGRATION_TEST` |
| `site/ja/Klz9Test.kt` | `Klz9IntegrationTest.kt` | 同名 | `KLZ9_INTEGRATION_TEST` |
| `site/ja/RawkumaTest.kt` | `RawkumaIntegrationTest.kt` | 同名 | `RAWKUMA_INTEGRATION_TEST` |
| `site/ja/WelomaTest.kt` | `WelomaIntegrationTest.kt` | 同名 | `WELOMA_INTEGRATION_TEST` |

> 两个文件名与类名原本就不一致（`CopyMangaImageLoadTest.kt` 里是 `CopyContentImageLoadTest`），改名时保留了这个关系，只统一后缀。

### B. 实证揪出、静态扫描漏掉的凭据事故（1 个）

`site/zh/CopyMangaAuthTest.kt`（类名 `CopyContentAuthTest`）里**硬编码了真实账号密码**，并且每次 `./gradlew test` 都会真的去登录。
它没被静态扫描抓到的原因：它走的是 `credentials.login()` 而不是解析器挂起方法，恰好绕过了启发式：

- 已改名为 `CopyContentAuthIntegrationTest.kt` + `COPYMANGA_INTEGRATION_TEST` 门控；
- 凭据改为只从 `COPYMANGA_USERNAME` / `COPYMANGA_PASSWORD`（或预置 `COPYMANGA_TOKEN`）读取；
- 三者都没有时用 `Assumptions.abort` **跳过**而非失败——缺凭据是环境问题，不是解析器回归；
- 已扫描全仓库 `src/{main,test}/kotlin`，无其他硬编码凭据残留。

⚠️ 凭据仍在 git 历史里。清理历史属于改写历史，未获明确指示不做；如该账号密码仍在别处复用，建议先改密码。

### C. 实证判定为误报，未改动（9 个）

| 文件 | 为什么不是联网 |
|---|---|
| `StandardEbooksTest.kt` | `chapter page carries ebook format marker` 用 fixture 解析后调用纯函数 `getPages`（`StandardEbooks.kt:257` 是 `listOf(...)`） |
| `InternetArchiveTest.kt` | `getPages` 纯本地，`getPageUrl` 就是 `page.url` |
| `LibraryGenesisTest.kt` | 只调用 `getPages`，纯本地（联网的是 `getPageUrl`，用例没调） |
| `EromeTest.kt` | `getPages` 走 `media:` 分支，立即返回，不到 `images:` 的 `webGet` |
| `Dianying88Test.kt` / `LuoqiuTest.kt` / `NoveliaWenkuTest.kt` | `assertThrows<ParseException>` 与 `getPages`/`getListPage` 均为本地校验路径 |
| `GuaziTest.kt` / `MkzhanTest.kt` | 只调用 `getPageUrl`，且输入来自 `fixture(...)` 解析出的本地页面，纯字符串计算 |

这些文件保留 `runBlocking` 包裹纯函数，虽略显多余，但**不构成隐藏联网**，故不动，避免为风格做无收益改动。

## 新增：离线护栏（`ContentLoaderContextMock`）

问题根源在于 `ContentLoaderContextMock` 用的是**真的 `OkHttpClient`**，所以"测试忘了打开离线夹具"这件事没有任何机制能兜底。现在补上：

- 只要没有设置任何 `<SOURCE>_INTEGRATION_TEST=1`，`ContentLoaderContextMock.httpClient` 就挂上一个拦截器，
  任何非回环地址（`127.0.0.1` / `localhost` / `::1`）请求立刻抛 `IOException`，消息里直接写明两种正确做法；
- 抛 `IOException` 而不是别的异常是有意的：原本会吞掉网络异常的用例行为保持不变（与断网跑等价），只是原因从"连接被拒"变成人话；
- 回环地址放行，本地 mock server 方案不受影响；
- 一旦设置了任一 `*_INTEGRATION_TEST=1`，护栏自动关闭，53 个 IntegrationTest 照常跑线上；
- 自测在 `OfflineNetworkGuardTest`：一条断言护栏会拦，一条断言回环放行，两条在默认 `./gradlew test` 下都真实执行（非跳过）。

代价是"粒度"：打开任一 IntegrationTest 会同时对所有类关闭护栏。这是刻意的取舍——用一次显式环境变量换来零配置的默认安全。

## 复现与验证

```bash
# 默认离线跑（护栏生效）
./gradlew test --no-daemon

# 用网络黑洞复核"确实没有偷偷联网"（比护栏更严格，连本地 mock 也不通）
HTTP_PROXY=http://127.0.0.1:1 HTTPS_PROXY=http://127.0.0.1:1 \
ALL_PROXY=http://127.0.0.1:1 ./gradlew test --no-daemon --continue

# 只跑某个线上验证
WELOMA_INTEGRATION_TEST=1 ./gradlew test --tests "*WelomaIntegrationTest" --no-daemon
```

实测：黑洞跑与默认跑均 `BUILD SUCCESSFUL`；全量 694 用例、0 失败、0 错误（140 个测试类，138 个线上用例因未设环境变量跳过）。

## 第二批之一：把"能连通"升级成离线回归（已完成）

上一批的 12 个文件只是被门控，验证的仍是"线上还能连通"。这一批把其中可离线化的 7 个站点改成**夹具回放**测试，
默认 `./gradlew test` 下真实执行、零联网：

| 站点 | 测试类 | 夹具 | 覆盖到 |
|---|---|---|---|
| klz9 | `site/ja/Klz9Test.kt` | `fixtures/klz9/`（3 个 JSON） | 列表 → 详情 32 章 → 46 页 |
| kiutaku | `site/all/KiutakuTest.kt` | `fixtures/kiutaku/`（6 个 HTML） | 列表 20 条 → 1 章 → 99 页（跨 5 个分页） |
| rawkuma | `site/ja/RawkumaTest.kt` | `fixtures/rawkuma/`（2 JSON + 3 HTML） | WP REST 列表 → ajax 章节 → 69 页 |
| weloma | `site/ja/WelomaTest.kt` | `fixtures/weloma/`（3 HTML） | 列表 20 条 → 32 章 → 46 页 |
| dmbus | `site/zh/DmbusTest.kt` | `fixtures/dmbus/`（4 HTML + 1 JSON） | 列表 36 条 → 12 章 → m3u8 播放列表（含解析接口） |
| wnacg | `site/galleryadults/zh/WnacgParserOfflineTest.kt` | `fixtures/wnacg/`（4 HTML） | 列表 20 条 → 站点章节 → 61 页（含 `verify` 防盗链参数） |
| copymanga | `site/zh/CopyContentParserTest.kt` | `fixtures/copymanga/`（4 JSON） | 列表 3 条 → 3 章 → 3 页（`webp` 转码） |

做法与取舍：

- **夹具只留解析器要用的部分**。整页 HTML 里绝大部分是导航、标签墙、广告和站点自身 JS，对断言毫无贡献。
  裁剪方式放弃"按正则删属性"（不可验证），改成两种可验证的手段：
  1. 命中解析器实际使用的选择器的子树才保留（见 `Weloma.kt` 的 `div.thumb-item-flow`、`ul.list-chapters.at-series`、`img.chapter-img[data-img]` 等）；
  2. 同一父节点下重复的兄弟元素只留前若干个。
- **裁剪结果由测试本身当裁判**：先算出"未裁剪夹具"的解析结果作为基准，再逐步加大裁剪力度，取仍能复现同一结果的最小版本，
  因此不存在"裁过头但没人发现"的情况。weloma 从 588 KB 压到 64 KB、kiutaku 从 1.1 MB 压到 164 KB。
- **条目/章节按标记定位**，不依赖 `first()`：抓包只覆盖了其中一条内容，列表顺序在裁剪后可能变化，
  测试里用 `list.first { "/7440" in it.url }` 这样的写法显式挑出被夹具覆盖的那条。
- **断言写死具体数值**（列表条数、章节数、页数、首图前缀），夹具是冻结快照，所以这些数值是稳定的；
  一旦解析规则回归，测试立刻变红，这正是上一批缺的那层保护网。
- 线上连通性验证仍然保留在对应的 `*IntegrationTest` 里，两者分工不同：离线锁规则、线上测可用性。

### 无法离线覆盖的两个源

| 站点 | 原因 | 结论 |
|---|---|---|
| buondua | CloudFlare 挑战页（`CloudFlareProtectedException`，抓到的只有 5 KB 验证页） | 不做夹具，只能靠 `BUONDUA_INTEGRATION_TEST=1` 线上验证 |
| picacg | 接口需要 `Authorization` 签名头（`AuthRequiredException`），抓包内容为空壳 | 不在仓库里存凭据，保持线上门控 |

## 第二批待办（本次未做）

2. **`requestInterval` 是死旋钮**：`WebSelectorParser.requestInterval` 有文档、`Fanqie`/`Fantuan` 都覆盖成 3000ms，
   但全仓库**没有任何消费者**——即这两个源实际从没被限速。要么接上，要么删掉，别让配置撒谎。
3. **13 个站点内联写死 `Mozilla/5.0`**，而 `UserAgents` 里已有常量，统一替换可避免下次改 UA 漏站点。
4. `ContentLoaderContextMock` 里的 PICACG/COPYMANGA token 预置逻辑与 `println` 调试输出，建议收敛成一个小的 `TestCredentials` 工具。

## 附：为什么这类问题会反复出现

对照外部项目 `ch6vip/novel-sound` 的做法，它把"上游身份/网络"全部收敛到 `UpstreamIdentity` 与 `mock_origin` 一个缝里，
测试只替换那个缝。我们这边的对应物是 `ContentLoaderContext`——缝是存在的，只是默认值是"真联网"，
且 112 个测试文件都在用它。护栏把默认值掰向了安全侧，但要真正降低复发率，还得让"新写测试"的默认路径就是夹具回放。

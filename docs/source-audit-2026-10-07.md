# 饭团、UZVOD 与 Hanime 排查记录

## 结论与改动

- 饭团详情页的广告懒加载图片位于真实封面之前。原通用选择器没有匹配 `img.anime-cover`，
  随后取全页第一张 `data-src` 图片，导致不同作品的详情都显示同一张广告图。
  现在只从作品封面区域读取图片；找不到时保留列表封面，不再猜测全页图片。
- 饭团默认列表原先使用空关键词搜索，搜索选择器依赖具体嵌套层级，翻页也只追加 `page` 参数。
  现在匹配 `.anime-card`，使用网站原生 `/ft/.../file/.../page/...` 和 `/search/page/.../wd/...` 路径。
- UZVOD 默认列表也落入空关键词搜索，浏览选择器与当前模板不符。
  现在使用 `/vodshow/` 浏览路径，匹配实际作品标题，排除推荐区域，并按原生路径传入排序、分类和页码。
  浏览路径的页码字段下标为 8，搜索路径为 10，不能共用一个参数位置。
- 两个源从配置域名生成请求地址，修正过度转义的视频正则。
  共享播放器解析使用 JSON 读取配置，支持百分号和 Base64 编码，保留完整签名查询参数及页面请求头。
  任意 iframe 不再被当作可播放视频返回。
- Hanime 已从 Nuxt 更换为 Astro。旧 `cached.freeanimehentai.net` API 无法解析，
  新公开索引为 `guest.freeanimehentai.net/api/v11/search_hvs`，响应从数组变为含 `data` 的对象。
  现在使用该索引恢复列表、搜索、标签及详情，播放路径迁移到 `/videos/hentai/`，同时兼容旧收藏章节路径。
  API 请求失败会抛出异常并允许重试，不再缓存为永久空列表；正常空搜索也不再回退到无关热门作品。
- Hanime 当前播放页没有直接流地址，而由浏览器播放器通过 `/api/v11/handshake` 获取播放数据。
  本次没有实现该新播放器流程，**原生播放仍未恢复**。浏览器动作请求不代表已取得可播放流。

## 为什么旧测试没有发现

1. `AnimekoWebSelectorIntegrationTest` 和 `NewVideoParserIntegrationTest` 原先只累计分数并打印日志。
   异常被捕获，错误和空结果没有触发 JUnit 断言，Gradle 因而可能显示成功。
2. 封面只检查 `http` 前缀，且只检查第一部作品；广告图片完全满足这一条件。
   没有比较不同作品，也没有验证图片响应和封面区域。
3. 播放只检查结果非空或 URL 前缀，没有请求流或校验 HLS 内容，广告 iframe 也可能被判为成功。
4. 直接调用 `getListPage(1)`，绕过了应用真实的 offset 分页和 Hanime 的零起始页。
5. 通用 `ContentSources` 参数化测试仅配置了 `WNACG`、`COPYMANGA`，没有覆盖这三个源。
6. 缺少可重复的离线夹具，在线波动与解析回归混在一起。

## 验证

新增 17 个离线用例，覆盖跨作品封面、广告在前、缺失封面回退、原生路径、配置域名、
搜索标题、章节、播放器编码、签名参数、API 响应结构、失败重试及旧章节迁移。
复用现有离线上下文和网络工具，没有新增依赖；公共测试助手负责断言和响应检查。

最终离线及在线门控检查使用：

结果：17 个离线用例全部通过，12 个未显式启用的在线用例跳过，构建成功。

```powershell
cmd /c "gradlew.bat test --tests org.skepsun.kototoro.parsers.site.zh.WebSelectorParserTest --tests org.skepsun.kototoro.parsers.site.en.HanimeTest --tests org.skepsun.kototoro.parsers.site.zh.AnimekoWebSelectorIntegrationTest --tests org.skepsun.kototoro.parsers.site.en.NewVideoParserIntegrationTest --no-daemon -Porg.gradle.java.installations.paths=D:/Java/jdk8"
```

没有运行全仓库 `test`；仓库另有未门控的真实网络测试。本次只运行任务相关测试类。
最初 Gradle 未识别已有 JDK 8，显式指定本机工具链路径后编译和测试可执行。

在线检查通过当前命令进程的 `<SOURCE>_INTEGRATION_TEST=1` 显式开启。
本次运行过以下用例：

| 用例 | 实际结果 |
| --- | --- |
| `AnimekoWebSelectorIntegrationTest.testFantuan` | 通过搜索、分页、多个详情封面、章节及流响应检查 |
| `AnimekoWebSelectorIntegrationTest.testUzvod` | 最后直连运行通过完整链路；早期代理运行触发 Cloudflare |
| `AnimekoWebSelectorIntegrationTest.testUzvodPlayback` | 独立重测时网络超时，没有将其吞掉或报告成功 |
| `NewVideoParserIntegrationTest.testHanimeCatalog` | 本机已有代理下通过；直连重测连接重置 |
| `NewVideoParserIntegrationTest.testHanime` | 明确失败于浏览器播放动作，原生流解析尚未恢复 |

在线结果反映本次网络和站点状态，不能据此保证长期可用。没有绕过验证码、登录或付费限制。
已将相关集成测试改为按源门控，错误会真实失败；默认运行不会访问这些站点。

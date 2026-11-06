# 国内流行的免费漫画 / 小说 / 视频站点（2026-11-05 实测）

范围：面向个人研究，只收录**不强制付费**的站点；正版付费墙站点只作对照。本文档替代 `docs/zh_content_sources_research.md`（2026-10-28）中过期的域名结论，未变部分仍可参考该文的结构分析。

仓库基线：`summary.yaml` total 96（zh 52 / en 21 / ja 4 / all 18 / pt 1）。上游对照：Keiyoushi `extensions-source` `src/zh` 2026-11-05 检出 60 个中文源；爱站排行榜「动画漫画 / 小说阅读」分类前 160 条。

## 结论速览

| 类型 | 现在真正在用的免费站 | 一句话判断 |
|---|---|---|
| 漫画（正版，免费章为主） | 腾讯动漫 `ac.qq.com`、快看 `kuaikanmanhua.com`、咚漫 `dongmanmanhua.cn` | 内容合法、结构 SSR，适合「只解析公开免费章」，付费章显式报错 |
| 漫画（第三方，全免费） | 拷贝漫画 `mangacopy.com`、漫画柜 `manhuagui.com`、包子漫画 `webmota.com`、极速漫画 `1kkk.com` / 动漫屋 `dm5.com`、漫客栈 `mkzhan.com` | 体量与更新速度最好；`1kkk`/`dm5` 作品链接由 JS 注入，成本高 |
| 小说（免费平台） | 番茄小说 `fanqienovel.com`、七猫 `qimao.com` | 体量最大，正文走接口/字体混淆，接入难度最高 |
| 小说（免费聚合） | 落秋 `luoqiuzw.com`、全本 `quanben.io`、qb5 `qb5.io`、52书库 `52shuku.net`、乐文 `lewen.org`、书海阁 `m.shuhaige.net`、阅友 `sma.yueyouxs.com`、笔趣阁55 `biquge55.net`、追书神器 `zhuishushenqi.com` | 结构简单、当天可落地；内容多为转载 |
| 视频（动漫向） | AGE动漫 `agedm.io`、动漫456 `dm456.com`、风车动漫 `fcbbcc.com`、蜜柑计划 `mikanani.me`（BT） | 播放普遍走第三方解析或 m3u8，逐集=chapter 可映射 |
| 视频（短剧） | 红果短剧 `hongguoduanju.com` | 2025–2026 增长最快的一类免费内容 |

**必须知道的两个变化**：动漫之家 `dmzj.com` 已全域不可达（社区称关站）；「樱花动漫」老域名 `yhdmz.org` 已不再提供片库（`m.yhdmz.org` 直接跳到博彩落地页），现在挂着「樱花动漫官方门户」名号的是一组 Next.js 同模板站群。

## 一、漫画

### A. 正版 / 大型平台（差异化最高）

| 站点 | 现役域名 | 2026-11-05 实测 | 免费程度 | 技术形态 | 仓库 |
|---|---|---|---|---|---|
| 腾讯动漫 | `ac.qq.com` | 200 / 101 KB，列表页 60 个 `comicInfo/id/` | 免费章 + V 券付费章 | 列表/详情 SSR；阅读页正文在 `var DATA`（base64 + 噪声） | ✅ 已内置（`TencentComicsParser.kt`，2026-11-05） |
| 快看漫画 | `kuaikanmanhua.com` | 200 / 208–229 KB，首页 61 个 `/web/topic/`；**详情页 `/web/topic/{id}/` 只有 7.9 KB 空壳**（`__NUXT__` 里 `topicInfo:{}`、`comics:[]`） | 免费为主 + 付费/会员章 | SSR 数据是空占位，真实取数走 `api.kkmh.com`，`/v1/topics/{id}` 返回 `code:3504`（需签名），`/v1/comic/{id}` 只回 `code:200` 无数据；详情页没有任何章节 HTML | ❌ 本轮不接入（见第八节） |
| 咚漫 Webtoon 中文 | `dongmanmanhua.cn` | 200 / 205–231 KB，首页 92 个 `title_no=` | 免费 + 等就免费券 | 列表/详情 SSR，`title_no` 为稳定 ID；阅读页 `#_imageList` 给 `cdn.dongmanmanhua.cn` 原图（去掉 `?x-oss-process` 即原画质，无防盗链） | ✅ 已内置（`Dongmanmanhua.kt`，2026-11-05） |
| 哔哩哔哩漫画 | `manga.bilibili.com` | 200 / >400 KB（读取截断） | 以付费/券为主 | 需登录态 | 未内置（合规边界，不建议） |

### B. 第三方聚合（全免费，体量大）

| 站点 | 现役域名 | 实测 | 技术形态 / 备注 | 仓库 |
|---|---|---|---|---|
| 拷贝漫画 | `mangacopy.com`（`www.` 同） | 200 / 99 KB（需代理）；`copymanga.tv` 已不通 | 与仓库 `CopyMangaParser` 同协议，预设里已有 `api.mangacopy.com` | ✅ 已内置 |
| 漫画柜 | `manhuagui.com` | 200 / 162 KB | 老牌全量库 | ✅ 已内置 |
| 包子漫画 | `webmota.com`（`baozimh.org` → `cn.bzmgcn.com` 返回「正在验证浏览器」403） | 200 / 179 KB，237 个作品链接 | 上游插件已把默认域名改为 `webmota.com`；本站有浏览器验证 | ✅ 已内置（预设含 `webmota.com`） |
| 极速漫画 / 动漫屋 | `1kkk.com` / `dm5.com` | 200 / 392 KB、403 KB，但首页 **0 个静态作品链接** | 作品链接由脚本注入，图片混淆，需 unpacker | 未内置 |
| 漫客栈 | `mkzhan.com` | 200 / 117 KB | SSR，作品带 `data-price` / `data-is_vip` | ✅ 已内置（`Mkzhan.kt`，2026-11-06） |
| 漫画人 | `manhuaren.com` | 200 / 12 KB（应用壳） | 走 `mangaapi.manhuaren.com` 移动 API | 未内置 |
| MangaBZ | `mangabz.com` | 200 / 47 KB（需代理） | 站前 Cloudflare，图片懒加载 | 未内置 |
| 8comic 無限動漫 | `8comic.com` | 200 / 198 KB（需代理） | 阅读页需 JS 解包 | 未内置 |
| 看漫网 | `kanman.com` | 200 / 153 KB | 爱站漫画榜前 30 | ✅ 已内置（`Kanman.kt`，2026-11-06）：PC 分页已坏，实际用 `/api/getsortlist/` 公开接口翻页；阅读页签名链接需回源防 CDN 旧缓存 |
| 92漫画 | `92mh.com` | 200 / 77 KB（2026-11-06 直连已恢复） | SinMH 模板：列表 `/list/{标签-}/{排序}/?page=N`、搜索 `?keywords=`、图片域名运行时取自 `/js/config.js` | ✅ 已内置（`Jiuermanhua.kt`，2026-11-06） |
| vomic漫 | `vomicmh.com` | 200 / 118 KB | 上游 `vomic` 源；需登录看部分作品 | 未内置 |
| 优酷漫画 | `ykmh.net` | 200 / 87 KB | — | ✅ 已内置 |
| 再漫画 | `manhua.zaimanhua.com` | 200 / 39 KB | — | ✅ 已内置 |
| Komiic | `komiic.com` | 200 / 6 KB（需代理） | 社区图源 | ✅ 已内置 |
| 滴答 / 六漫画 | `ddmanhua.com` / `liumanhua.com` | 200 / 98 KB、50 KB | 同库同协议，已合并为单源 | ✅ 已内置 |
| 瓜子漫画 | `guazimanhua.com` | 200 / 39–83 KB，分类页 24 166 部（36 条/页，672 页） | 分类 `/category.php?page=N`（`sort=update\|hits\|daily\|score`，`cid=` 23 个题材）→ `article.card`；详情/目录同时有 JSON-LD（`ComicStory` + `ItemList`，**50 话以上截断**）和 HTML `[data-mobile-chapter-list]`（481 话全量）；阅读页 `section.reader-images img.reading-image` webp 直链无防盗链；无搜索入口 | ✅ 已内置（`Guazi.kt`，2026-11-05） |
| 漫画社 / 喜漫 / 漫画160 / 喵趣 / 漫画屋 | `311s.com` / `favcomic.com` / `mh160mh.com` / `miaoqumh.org` / `mhua5.com` | 全部 200（48–151 KB） | 各自独立 CMS，体量小，上游仍在维护 | 漫画社 ✅ 已内置（`Manhuashe.kt`，2026-11-06）；其余四站 ⏸ 暂缓：2026-11-06 直连复核 `favcomic.com` 连接重置、`miaoqumh.org` TLS 握手超时、`mhua5.com` 首页 88 KB 可读但详情页 TLS 超时、`mh160mh.com` 200 / 62 KB 是另一套 `template/skin1` 模板（与漫画社的 `/comic_{id}.html` 不同源），本机当前无代理无法逐个验证 |
| 顶通 Toptoon 国际中文 | `toptoon.net` | 200 / 73 KB（需代理） | 模板渲染 + 接口 | 未内置 |
| 香香腐宅 / 鸟鸟韩漫 / 漫蛙2 | `boylove.cc` / `nnhanman.xyz` / `manwa2.com` | 200 / 200 KB、46 KB（代理）、60 KB（直连） | 成人向，仓库已有同类的 `HENTAI_*` 风格源 | 未内置 |

### C. 已死 / 不建议

- **动漫之家 `dmzj.com`**：`www` DNS 不解析、`manhua.dmzj.com` 连接被重置，代理也连不上；社区帖（NGA「dmzj彻底土间埋了」、小红书「漫画之家终究还是倒下了」）称已关停。原 `extensions-source` 的 `dmzj` 扩展也已不在 `src/zh` 列表里。
- MyComic `mycomic.com`：403 Cloudflare 拦截页。
- 如漫画 / 读漫画屋：`rumanhua2.com` TLS 失败、`dumanwu1.com` 详情页为 APK 下载跳板。
- 36漫画 / 漫画台类「官网只有 APK 下载页」：无 Web 阅读链。

## 二、小说

### A. 免费大平台

| 站点 | 域名 | 实测 | 免费程度 | 难点 | 仓库 |
|---|---|---|---|---|---|
| 番茄小说 | `fanqienovel.com` | 200 / 142–163 KB，首页 23 个 `/page/` | 全免费（广告） | 正文走 `api5-normal-lf.fqnovel.com` + 字体混淆 | ❌ 放弃（接口 + 字体混淆，见十三） |
| 七猫中文网 | `qimao.com` | 200 / 268–301 KB，236 个书页链接 | 全免费（广告） | 阿里云 WAF 已不再拦截首页，接口需重新确认 | ❌ 放弃（签名接口，见十三） |
| 刺猬猫 | `ciweimao.com` | 200 / 135 KB | 免费 + 付费章 | 登录混合 | ❌ 放弃（客户端解密 + 登录，见十三） |
| 晋江 / 起点 / 纵横 / 17K / 塔读 | `jjwxc.net`(200, GBK) / `qidian.com`(202 挑战) / `zongheng.com`(200 SPA) / `17k.com`(200) / `tadu.com`(200) | — | 付费为主 | 免费章有限 | ❌ 放弃（17K 章节页 405、其余 SPA/付费，见十三） |
| SF轻小说 | `book.sfacg.com` | 200 / 64 KB 详情、13–78 KB 目录 | 原创轻小说，免费章 + VIP 章 | 必须桌面 UA（移动 UA 下发 12 KB 精简页）；书库 `/List/`、目录按卷分组 | ✅ 已内置（`Sfacg.kt`，2026-11-06） |

### B. 免费聚合站（结构简单，当天可落地）

| 站点 | 域名 | 实测 | 备注 | 仓库 |
|---|---|---|---|---|
| 落秋中文 | `luoqiuzw.com` | 200 / 34–47 KB，首页 138 个作品链接 | 列表 `/class/{0..7}/{页}.html`（30 条/页）；详情全在 `og:novel:*` meta；目录页 `#list dd` 只给前 99 章，其余在 `/chapters/{id}/{页}.html`（末页「下一页」会绕回第 1 章）；正文 `document.writeln(qsbs.bb('<base64>'))`，解 base64 即得 `<p>` 段落；`/search.html` 恒返回空占位页 | ✅ 已内置（`Luoqiu.kt`，2026-11-05） |
| 全本小说 | `quanben.io` | 200 / 41 KB | 路由 `/n/{slug}/`；分类 `/c/{slug}_{页}.html`、`div.list2` 卡片、详情 `og:novel:*` + `div.description`、目录 `/n/{slug}/list.html` 的 `ul.list3` 一次给全、正文 `div#content`；`index.php?c=book&a=search&keywords=` 搜索可用 | ✅ 已内置（`Quanben.kt`，2026-11-05） |
| 全本小说书屋 | `qb5.io` | 200 / 14 KB | 爱站小说榜前 30；分类 `/shuku-{1..11,0}_{页}.html`、`dl.hot dd` 卡片、详情 `og:novel:*`（简介只有被截断的 `og:description`）、目录 `/xs-{id}/` 的 `ul.list` 一次给全 3818 章、正文 `div.articlebody#content`；`/?c=xs&a=sou&q=` 返回空页 | ✅ 已内置（`Qb5.kt`，2026-11-05） |
| 52书库 | `52shuku.net` | 200 / 26 KB | 分类 `/wuxia/` 等 | ⏸ 暂缓（需代理，见十三） |
| 乐文小说 | `lewen.org` | 200 / 20–27 KB，列表 66 条/页 | 列表 `/list/{1..9}_{页}.html`、详情 `og:novel:*` 齐全，但**详情页只给最近 112 章**且无全本目录页（`/book/{id}/all.html` 404），搜索 `search.html` 500、`/search/?keyword=` 404 | ❌ 放弃（403 + 目录不全，见十三） |
| 书海阁 | `m.shuhaige.net` | 200 / 14 KB | 移动站 | ✅ 已内置（`Shuhaige.kt`，2026-11-06） |
| 阅友小说 | `sma.yueyouxs.com` | 200 / 71–73 KB | — | ❌ 放弃（0 个锚点，纯 JS 绑定，见十三） |
| 笔趣阁55 | `biquge55.net` | 200 / 24 KB，列表 60 条/页 | 分类 `/xiaoshuo/{xuanhuan\|wuxia\|doushi\|chuanyue\|kehuan\|qita}_{页}/`；详情 `og:novel:*` + `div.desc` + `#list` 一次性给全 3404 章；长章节 `{id}_2.html` 分页、「下一页」串联；`/search/?q=` 恒空 | ✅ 已内置（`Biquge55.kt`，2026-11-05） |
| 101言情 | `txtxs101.com` | 200 / 34 KB | 言情向 | ⏸ 暂缓（需代理，见十三） |
| 随心看 | `m.suixkan.com` | 200 / 69–75 KB | — | ❌ 放弃（同阅友，见十三） |
| 追书神器 | `zhuishushenqi.com` | 200 / 33 KB | 老牌聚合 | ⏸ 暂缓（限速断流，见十三） |
| 笔趣阁（仓库版） | `bqg301.cc` | 200 / 1 KB，跳 `bqg867.cc` | 域名漂移中 | ✅ 已内置 |

### C. 反爬 / 不可达

- `69shuba.com` 403（无挑战标记，疑似 UA/频率限制）；`hetushu.com`、`81zw.com`（→`81zw2.com`）Cloudflare `Just a moment`。
- `69shu.com` 代理下也是 CF 403；`zxcs.me` 直连与代理都连不上；`23us.so` 两端超时；`bookfedex.com` TLS 失败；`gongzicp.com`（长佩）仅 4 KB 壳；`shuqi.com` 403。

## 三、视频 / 动漫 / 短剧

| 站点 | 域名 | 实测 | 播放形态 | 仓库 |
|---|---|---|---|---|
| AGE动漫 | `agedm.io`（`age.tv` 同源） | 200 / 133 KB，首页 166 个 `/detail/`；详情页 797 KB / 9332 个 `/play/{id}/{sid}/{ep}` | 播放页只剩一个 `#iframeForVideo`，指向第三方解析 `jx.wuzhoupai.com:8443`：`sid=2..5`（非凡/暴风/无尽/计算云）的 `…/m3u8/?url=age_…` 响应里 `var Vurl` 就是 m3u8 直链；`sid=1`（西瓜）的 `…/vip/?url=age_…` 里 `var Vurl` 是 96 位十六进制密文，靠页面自带的 `global.min.js`（CDN 域已不可解析）现场解密 | ✅ 已内置并修复播放链路（`Age.kt`，2026-11-06）：直链正则优先，`/vip/` 线路显式报「请切换线路」；实测 5 条线路 3 条返回 `200 + #EXTM3U` |
| 动漫456 | `dm456.com` | 200 / 132–143 KB | MacCMS「conch」模板：列表 `/fenlei/{码}/`（10 个大小写敏感频道码，36 部/页）、详情 `/donghua/{id}/`、播放页 `player_aaaa` 里直接给 m3u8 直链（无防盗链） | ✅ 已内置（`Dm456.kt`，2026-11-05） |
| 风车动漫 | `fcbbcc.com`（`fengchedm.com` 跳转） | 200 / 55–71 KB；**HTTPS 握手失败，只能 http** | 列表 `/list/{数字码}-{页}.html`（40 部/页）、详情 `/content/{id}.html`；搜索关键字走 Cookie `keyww`；播放页 `paly_js` 混淆 base64（`-`→`a`、`.`→`=`）解出 m3u8；手机 UA 会切到 DM530 手机版，必须固定桌面 UA | ✅ 已内置（`Fengche.kt`，2026-11-05） |
| 「樱花动漫」站群 | `dongman.hk`、`dongman88.cn`、`hcamelot.com`、`kissanime.hk`、`kissanime.tw`、`donghua.hk`、`9anime.hk`、`yhdm88.cn`、`yhdm365.cn`、`kotakanime.net`，以及同名影视站 `dianying88.*`、`555xs.com`、`jihai88.com`、`wn55.com` | 全部 200 / ~150 KB，同一套模板 | Next.js App Router，现行路由是 `/movie/{id}`、`/category/{slug}`、`/search?q=`（文首记录的 `/yhzx`、`/yhss?wd=` 已被站点换成这套路由） | ✅ 已内置（`Yhdm.kt`，2026-11-06） |
| 樱花动漫（老站） | `yhdmz.org` | `www` 200 但正文 0 KB（`Loading...` 壳）；`m.yhdmz.org` 302 → `adeli-msh.com/...` 博彩落地页 | 品牌域名已不再提供片库 | ❌ 放弃（域名不再提供片库，见十三） |
| 蜜柑计划 | `mikanani.me` | 200 / 150 KB（需代理） | BT 种子，不适合「章节=页面」，只做元数据 | ❌ 不适用（BT 种子，见十三） |
| 动漫花园 | `share.dmhy.org` | 200 / 400 KB（需代理） | 同上 | ❌ 不适用（BT 种子，见十三） |
| 爱剧迷 Gimy | `aigm.tv` | 200 / 161 KB（需代理） | 播放页 `/play/{混淆 token}` 需逆向 | ❌ 放弃（token 逆向，见十三） |
| 爱看机器人 | `ikanbot.com` | 200 / 27 KB | 播放指向第三方 iframe | ❌ 放弃（SPA，见十三） |
| 低端影视 | `ddys.app` | 200 / 74 KB | 资源索引（磁力/网盘），不适合逐页模型 | ❌ 不适用（磁力/网盘索引，见十三） |
| 欧乐影院 | `olevod.com` | 200 / 7 KB（需代理） | 客户端路由 SPA，需接口逆向 | ❌ 放弃（SPA 接口逆向，见十三） |
| Libvio | `libvio.la` | 200 / 12 KB，正文即「域名暂时停止使用通知」 | 已停用 | ❌ 放弃（站点停用，见十三） |
| 稀饭动漫 / NyaFun / 嘶哩嘶哩 | `xifanacg.com` 不可达；`nyafun.net` 5 KB 跳转；`silisili.cc` 22 KB 已变导航站 | — | 不建议 | ❌ 放弃（不可达/导航站，见十三） |
| 红果短剧 | `hongguoduanju.com` | 200 / 183 KB 首页、详情页 706 KB | 番茄/红果 React SPA（SSR 出卡片与 JSON-LD `TVSeries`，`numberOfEpisodes` 有值）；**播放页是系列级**（`/player/{series_id}`，JSON-LD 里 168 个 episode 全部指向同一个 URL），全站无 `m3u8`/`play_url`，串流走字节签名接口 | ❌ 本轮不接入（见第八节） |
| 正版平台 | bilibili / 爱奇艺 / 腾讯视频 / 优酷 / 芒果 | — | 付费 + DRM，不绕过 | — |

## 四、与仓库覆盖对照

- 上游（Keiyoushi `src/zh`，2026-11-05，60 源）有、仓库没有且值得做的：`kuaikanmanhua`、`tencentcomics`（`m.ac.qq.com`）、`dongmanmanhua`、`dm5`、`mangabz`、`manhuaren`、`comicabc`（8comic）、`tongli`（東立電子書城）、`terrahistoricus`（明日方舟漫画站，✅ 已于 2026-11-06 实现，见第十五节）、`utifa`、`vomic`、`zazhimi`、`jiuermanhua`、`hanabimanga`、`noyacg`、`zerobyw`。
- 仓库有、上游已下线：动漫之家系（上游已移除）。
- 同名不同物，别弄错：仓库 `FANQIE` = 番茄动漫 `fqdm.cc`；`FANQIE` 与番茄小说 `fanqienovel.com` 无关。

## 五、接入优先级建议（个人研究向）

1. **腾讯动漫 `ac.qq.com`**（✅ 已完成）：列表 `/Comic/all/` → 详情（859 章级，SSR）→ 阅读页 `var DATA` base64 解码；`canRead=false` 显式抛付费异常。
2. **落秋中文 `luoqiuzw.com`**（✅ 已完成）：小说里成本最低，base64 正文无需执行 JS。
3. **咚漫 `dongmanmanhua.cn`**（✅ 已完成）：列表/详情 SSG，`title_no` 稳定；阅读页 `#_imageList` 直出图片。
4. **动漫456 `dm456.com`**（✅ 已完成）：播放页 `player_aaaa` 直接给 m3u8。
5. **风车动漫 `fcbbcc.com`**（✅ 已完成）：与动漫456 同属 MacCMS 家族，但皮肤不同（懒加载卡片、Cookie 搜索、混淆播放地址）。
6. **快看 `kuaikanmanhua.com`**（❌ 放弃）：SSR 空壳 + API 需签名，见第八节。
7. **红果短剧 `hongguoduanju.com`**（❌ 放弃）：播放页系列级、串流需字节签名，见第八节。
7. 暂缓：`1kkk`/`dm5`（JS 注入 + 图片混淆）、番茄小说（接口 + 字体反混淆）、七猫（接口待确认）、爱剧迷/欧乐（token 逆向）、樱花系站群（同一家运营，需先确认是否值得引入）。

## 六、取证方法与限制

- 环境：本机直连（国内网络）+ Clash `127.0.0.1:7890` 代理（出口 `89.125.70.102`，新加坡）双路径；时间 2026-11-05；Chrome 142 UA，`Accept-Language: zh-CN`；`python3 requests` 12–20 s 超时，每条记录最终 URL / 状态 / 体积 / `<title>` / 作品链接计数 / SPA-CF-WAF 标记。
- 流行度证据：爱站排行榜「动画漫画（t3-21）」「小说阅读（t3-17）」「视频电影（t3-15）」前 160 条。漫画榜前十是 bilibili、快看、极速漫画 `1kkk.com`、腾讯动漫、动漫屋 `dm5.com` 及各自移动站；漫画人第 20、看漫网第 26、漫客栈第 27、咚漫第 29。上游 `extensions-source` 当日检出 60 个 zh 源；Legado 书源合集 935 条样本的域名频次。
- 限制：爱站榜单由 SEO 权重+流量估算构成，盗版聚合站多被降权，所以「榜上有名」只能证明正版/大站流行，免费聚合站的流行度靠上游维护频次与社区口碑；DC/境外出口不可达的站（`toptoon`、`manwa`、`boylove`、`mikan`、`dmhy`）已标注「需代理」；`yhdmz.org` 系列仅凭响应特征判断，未做真机浏览器验证。

## 七、复核命令

```shell
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

# 漫画：列表页 SSR 链接数
curl -sL -A "$UA" https://ac.qq.com/Comic/all/ | grep -c 'comicInfo/id/'
curl -sL -A "$UA" https://www.dongmanmanhua.cn/ | grep -c 'title_no='
curl -sL -A "$UA" https://www.1kkk.com/ | grep -c 'manhua-[0-9]'   # 0 = 作品链接由 JS 注入
curl -sIL -A "$UA" https://www.copymanga.tv/ | head -1             # 已不通
curl -sIL -A "$UA" https://mangacopy.com/ | head -1

# 小说
curl -sL -A "$UA" https://www.luoqiuzw.com/ | grep -oE '/book/[0-9]+/[0-9]+\.html' | head -1
curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.69shuba.com/
curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.hetushu.com/

# 动漫 / 视频
curl -sIL -A "$UA" https://www.agedm.io/ | head -1
curl -sL -A "$UA" https://www.agedm.io/play/20260340/1/1 | grep -o '<iframe[^>]*' | head -2   # 第三方解析
curl -sIL -A "$UA" https://www.yhdmz.org/ | head -1
curl -sL -A "$UA" http://www.fcbbcc.com/list/137-1.html | grep -c 'content/[0-9]'
curl -sIL -A "$UA" https://www.hongguoduanju.com/ | head -1
```

## 八、本轮实现与验证（2026-11-05）

本轮把「结构简单、当天可落地」的 5 个站做成了仓库内可用的解析器，另有 2 个站点经实测后**明确放弃**并留下证据。

| 站点 | 类型 | 解析器 | 离线测试 | 在线验证 | 关键实现点 |
|---|---|---|---|---|---|
| 腾讯动漫 `ac.qq.com` | 漫画 | `TencentComicsParser.kt` | `TencentComicsParserTest` | `TencentComicsIntegrationTest`（`TENCENT_COMICS_INTEGRATION_TEST=1`） | 阅读页 `var DATA` 是 base64 + 噪声；`canRead=false` 显式抛付费异常 |
| 落秋中文 `luoqiuzw.com` | 小说 | `Luoqiu.kt` | `LuoqiuTest` | `LuoqiuIntegrationTest`（`LUOQIU_INTEGRATION_TEST=1`） | 详情全在 `og:novel:*`；目录 `/chapters/{id}/1.html++` 翻页上限；正文 `document.writeln(qsbs.bb('<base64>'))` |
| 咚漫 `dongmanmanhua.cn` | 漫画 | `Dongmanmanhua.kt` | `DongmanmanhuaTest` | `DongmanmanhuaIntegrationTest`（`DONGMANMANHUA_INTEGRATION_TEST=1`） | 搜索走 `?keyword=`；阅读页 `#_imageList`；同一话多域名副本需去重 |
| 动漫456 `dm456.com` | 视频 | `Dm456.kt` | `Dm456Test`（14 例） | `Dm456IntegrationTest`（`DM456_INTEGRATION_TEST=1`） | 10 个大小写敏感频道码；`player_aaaa` → m3u8；`encrypt=1` 是 base64 |
| 风车动漫 `fcbbcc.com` | 视频 | `Fengche.kt` | `FengcheTest`（12 例） | `FengcheIntegrationTest`（`FENGCHE_INTEGRATION_TEST=1`） | 只能 http；桌面 UA；搜索用 Cookie `keyww`（必须写 cookie jar）；`paly_js` 混淆 base64 |

### 放弃的两个站点（附实测证据）

**快看漫画 `kuaikanmanhua.com`**
- `/web/topic/{id}/` 响应仅 7.9 KB，`__NUXT__` 是压缩 IIFE，实测内容为 `topicInfo:{}`、`comics:[]`、`curCode:10104`——服务端不下发章节数据。
- 数据接口 `api.kkmh.com/v1/topics/{id}?limit=5` 返回 `{"code":3504,"message":"系统开小差啦～请重试"}`（签名/风控）；`/v1/comic/{id}`、`/v1/chapter/{id}` 只回 `{"code":200,"message":"ok"}` 无业务数据；`/v1/topics/{id}/items` 是 404。
- 结论：要接入必须逆向 `kuaikan` 的 Web 签名算法，属于易变且高维护成本的反爬对抗，超出「只解析公开免费章」的边界，本轮不做。

**红果短剧 `hongguoduanju.com`**
- 详情页 706 KB、SSR 完整（JSON-LD `TVSeries` 带 `numberOfEpisodes`、简介、封面、`keywords` 标签），分类页 24 个 `series_id` 链接，列表侧完全可解析。
- 但 JSON-LD 里 168 个 `TVEpisode` 的 `url` **全部指向同一个** `/player/{series_id}`；播放页 164 KB 全站无 `m3u8`、无 `play_url`、无可见的 `/api/` 路径，串流由字节 `sdk-glue.js` 签名后的接口下发。
- 仓库现有 `ContentChapter` 需要「一集一个 URL」，红果 Web 端不满足；串流需逆向字节签名 SDK，同样不做。若要覆盖短剧，`HUANGGUO`（已内置）仍是可行路线。

### 本轮验证命令与结果

```shell
./gradlew compileKotlin --no-daemon                       # BUILD SUCCESSFUL，KSP 生成 DM456/FENGCHE
./gradlew test --tests "*Dm456Test"                        # 14/14
./gradlew test --tests "*FengcheTest"                      # 12/12
DM456_INTEGRATION_TEST=1 ./gradlew test --tests "*Dm456IntegrationTest"
FENGCHE_INTEGRATION_TEST=1 ./gradlew test --tests "*FengcheIntegrationTest"
```

- 动漫456 在线：番剧首页 36 条、第 2 页不重复；《归还者的魔法要特别 第二季》5 集；首集 m3u8 `HEAD 200`、`Content-Type: application/vnd.apple.mpegurl`；搜索「鬼灭」12 条全相关；少儿频道 36 条带频道标签。
- 风车动漫在线：默认频道首页 40 条均有封面且 `publicUrl` 为 `http://`；第 2 页 40 条无重复；《归还者的魔法要特别第二季》5 集；首集 m3u8 `HEAD 200`；Cookie 搜索「吞噬」13 条全相关，第 2 页为空。
- `.github/summary.yaml`：total 96 → 101（本轮 +5）；README 视频区补「动漫456 / 风车动漫」两行。

## 九、第二轮补齐（2026-11-05 续）

在第八节 5 个站点之后，又按「结构简单、可离线固化」的标准补了 4 个站点，全部走「列表 + 目录 + 正文」纯 JSoup 路线，无 JS 执行、无签名接口。

| 站点 | 类型 | 解析器 | 离线测试 | 在线验证 | 关键实现点 |
|---|---|---|---|---|---|
| 笔趣阁55 `biquge55.net` | 小说 | `Biquge55.kt` | `Biquge55Test`（10 例） | `Biquge55IntegrationTest`（`BIQUGE55_INTEGRATION_TEST=1`） | `#list` 一次给全 3404 章（新章在前，需倒序）；长章节 `{id}_2.html` 按「下一页」串联，同页另有广告 `div.content` 需避开 |
| 瓜子漫画 `guazimm.com` | 漫画 | `Guazi.kt` | `GuaziTest` | `GuaziIntegrationTest`（`GUAZI_INTEGRATION_TEST=1`） | 详情与目录同时给 JSON-LD 和 HTML；正文 webp 直链带 referer |
| 全本小说 `quanben.io` | 小说 | `Quanben.kt` | `QuanbenTest`（7 例） | `QuanbenIntegrationTest`（`QUANBEN_INTEGRATION_TEST=1`） | 分类（12 部/页）与搜索（96 条/页且忽略 page 参数）共用 `div.list2` 卡片；目录 `ul.list3` 一次给全；章节标题只有「第N章」没有章名，编号缺失时按前一章 +1 |
| 全本小说书屋 `qb5.io` | 小说 | `Qb5.kt` | `Qb5Test`（10 例） | `Qb5IntegrationTest`（`QB5_INTEGRATION_TEST=1`） | 12 个 `/shuku-{id}.html` 分类（12 部/页）；目录 3818 章一次给全且顶部有重复链接要去重；正文 `div.articlebody#content`，页脚「下一页」指向下一章而非本章分页 |

两站共用 `Document.ogMeta()`（`util/Jsoup.kt`），键保留完整 `og:` 前缀；顺手修正了 `Biquge55` 里键被剥掉前缀却仍按 `og:novel:*` 取值、导致书名/作者/状态一直回落到兜底的隐藏缺陷。

### 本轮验证命令与结果

```shell
./gradlew compileKotlin --no-daemon                                        # BUILD SUCCESSFUL，KSP 生成 BIQUGE55/GUAZI/QB5IO/QUANBEN
./gradlew test --tests "*Biquge55Test" --tests "*GuaziTest" \
    --tests "*Qb5Test" --tests "*QuanbenTest"                             # 全部通过
QB5_INTEGRATION_TEST=1 QUANBEN_INTEGRATION_TEST=1 \
    ./gradlew test --tests "*Qb5IntegrationTest" --tests "*QuanbenIntegrationTest"
```

- qb5 在线：奇幻玄幻首页 60 条有封面/作者，第 2 页不重复，科幻同人分类非空；《傲世丹神》目录 3818 章正序、简介与作者齐全，正文页产出 `data:text/html`。
- quanben 在线：玄幻首页非空，第 2 页不重复，耽美分类与「玄幻」关键词搜索均非空；《寝室美狼》目录正序，正文页产出 `data:text/html`。
- `.github/summary.yaml`：total 101 → 105（本轮 +4）；README 小说区补「全本小说网 (qb5) / 全本小说网 (quanben)」两行。

## 十、第三轮补齐（2026-11-06）

按「只要站点结构可行就接入，不可行的留实测证据」继续推进。新增源与状态：

| 站点 | 类型 | 解析器 | 离线测试 | 在线验证 | 关键实现点 |
|---|---|---|---|---|---|
| 漫客栈 `www.mkzhan.com` | 漫画 | `Mkzhan.kt` | `MkzhanTest`（14 例） | `MkzhanIntegrationTest`（`MKZHAN_INTEGRATION_TEST=1`） | 列表 `/category/?order=1\|2&theme_id=N&page=N`（30 部/页，23 个真实题材）；目录 1000+ 话内联且 `a.j-chapter-link` 没有 href，地址在 `data-hreflink`，新章在前需倒序；阅读页本身不含图，图走公开接口 `comic.mkzcdn.com/chapter/content/v1/`，去掉 `!page-800?auth_key=` 后缀并换 `oss.mkzcdn.com` 即得原图；VIP 章该接口返回 `code=302 用户未登录`，按错误处理 |
| 书海阁 `m.shuhaige.net` | 小说 | `Shuhaige.kt` | `ShuhaigeTest`（8 例） | `ShuhaigeIntegrationTest`（`SHUHAIGE_INTEGRATION_TEST=1`） | 列表 `/{分类}/`、`/{分类}/{页码}.html`（20 部/页，页面里的「下一页」写成 `list_N.html` 但该地址 404，实测真实分页是 `{页码}.html`）；搜索是 `POST /search.html`（`searchkey`），结果页再换成 `/search/{id}/{页码}.html`；目录 `/{id}/`、`/{id}_{页码}/`（50 章/页，总页数在 `select option`）；正文只取 `div.content > p`，同页「大家在看」区块要丢弃 |
| 樱花动漫 `www.dongman.hk` | 视频 | `Yhdm.kt` | `YhdmTest`（8 例） | `YhdmIntegrationTest`（`YHDM_INTEGRATION_TEST=1`） | Next.js App Router 站群，域名走 `ConfigKey.Domain`；分类 `/category/{dongman\|fanju\|guochuang\|dianying}?page=N` 与 `/latest?page=N` 各 24 部/页，卡片 `ul.ws-grid > a.ws-card`（标题 `span.ws-card-name`、状态 `span.ws-card-remarks`、封面藏在 `srcSet` 的 `/_next/image?url=` 里，解码后拼站点域名可直取）；搜索 `/search?q=` 是另一套 `ul.ws-results > article.ws-result` 结构且忽略分页；详情正文在 `ld+json` 的 TVSeries，分集 `ol.ws-epgrid > a.ws-ep[title][href]` 指向 `/watch/{id}?s=&e=`；播放页 HTML 内联 `*.m3u8` 直链，无 JS 挑战、无防盗链。排行页 `/top` 是杂志式版面（`article.ws-topone`），与分类页结构不共用，故只暴露「最近更新」排序 |
| SF轻小说 `book.sfacg.com` | 小说 | `Sfacg.kt` | `SfacgTest`（9 例） | `SfacgIntegrationTest`（`SFACG_INTEGRATION_TEST=1`） | 书库 `/List/?tid={21..29}&if={0,1}&PageIndex={n}`（20 部/页）与写作进度筛选；搜索走 `s.sfacg.com/?Key=&S=1`（单页、无分页）；详情 `h1.title`/`.summary-pic img`/`.author-name span`/`p.introduce`/`ul.tag-list`；目录 `div.story-catalog` 按卷分组、整本一次给全；正文 `div#ChapterBody`。**桌面 UA 是硬要求**：默认/移动 UA 会拿到 12 KB 精简页与只有章节名的目录，解析必然为空 |
| 看漫网 `www.kanman.com` | 漫画 | `Kanman.kt` | `KanmanTest`（14 例） | `KanmanIntegrationTest`（`KANMAN_INTEGRATION_TEST=1`） | PC 端 `/sort/` 只有第 1 页可用（`index_p2.html` 实测 500），改走移动端公开接口 `/api/getsortlist/`（`orderby=click|date`、`comic_sort` 题材、`search_key` 搜索、`page`/`size`）；详情 `h1.title`、封面 `data-src`（去掉 `-300x400` 后缀即原图）、题材 `p.tags a`、状态 `.update-info p.status`；目录 `ol#j_chapter_list` 一次内联；阅读页图片在 `window.comicInfo.current_chapter.chapter_img_list`（签名 15–60 分钟、页面被 CDN 缓存 12 小时，需加随机参数回源） |
| 漫画社 `www.311s.com` | 漫画 | `Manhuashe.kt` | `ManhuasheTest`（6 例） | `ManhuasheIntegrationTest`（`MANHUASHE_INTEGRATION_TEST=1`） | 分类 `/category/`、`/category/page/{n}`（20 部/页）与题材 `/category/tags/{id}[/page/{n}]`（分类页把 292 个题材全列出来）；搜索是路径式 `/search/{关键字}[/page/{n}]`（30 部/页，`?keyword=`/`?q=` 都返回 0 条）；详情 `/comic_{id}.html` 的评分/人气/作者在 `.comic-stats .stat-item span`、题材与状态在 `.comic-tags span.tag`、目录 `.chapter-list .chapter-item a` 正序几百话内联；阅读页 `div.comic-content img.comic-image` 是 `manhua.5um.net` 直链 webp |

### 本轮实测为「不可行 / 暂缓」的站点

| 站点 | 结论 | 证据（2026-11-06 实测） |
|---|---|---|
| 阅友 `sma.yueyouxs.com` | ❌ 不可接入 | 首页 61 KB 有完整书目正文，但 `<a>` 标签数为 **0**（`grep -c '<a '` = 0），全靠 JS 绑定点击事件跳转；无 JS bundle 暴露、无 API 线索，无法在不跑浏览器的情况下拿到列表/详情 |
| 随心看 `m.suixkan.com` | ❌ 不可接入 | 与阅友同源同构（同一 `cdn.p.yueyouxs.com` 静态资源、同样 4 个外链、无作品锚点），属于同一套 JS 前端 |
| 101言情 `www.txtxs101.com` | ⏸ 需代理 | 本机直连 `Connection reset by peer`（`http`/`https` 均被重置），经 Clash 代理 200 / 25 KB，结构是 `/id/{id}.html` 详情、`/id/{id}/{n}.html` 正文、`/class/{id}_{页}.html` 列表；可作为下一轮候选，但当前环境无法做在线验证 |
| 52书库 `www.52shuku.net` | ⏸ 需代理 | 直连 `SSLEOFError`，代理 200 / 21 KB；分类导航是 `/xiandaidushi/`、`/chongsheng/` 这类拼音目录，可做但同样无法在线验证 |
| 乐文 `www.lewen.org` | ❌ 不可接入 | 直连与代理均 403 Forbidden，目录本身不全（第十节说明） |
| 追书神器 `www.zhuishushenqi.com` | ⏸ 暂缓 | 详情页 548 KB 内联 7088 个章节链接，但正文页 `/book/{hash}/{n}.html` 在 25 s 超时内未返回完整响应（站点对大页面限速），在线验证不可靠 |

- 樱花动漫在线：分类第 1/2 页非空且无重复，`/latest` 非空，搜索「进击」34 条，详情 6 集正序，首集 m3u8 `HEAD 200` 且 `Content-Type: application/vnd.apple.mpegurl`（站群偶发连接超时，集成测试内置 3 次重试）。

- 漫画社在线：分类第 1/2 页各 20 条且无重复，题材「热血」筛选非空，搜索「一人」命中《一人之下》，详情 801 话正序、作者/题材齐全，末话首尾图片 `HEAD 200` 且 `image/*`。
- SF轻小说在线：`/List/` 第 1/2 页各 20 条且不重复，`if=1`（已完结）筛选非空，搜索「魔女」有结果且第 2 页为空（站点单页），抽样 5 部作品目录 14–64 章、卷名齐全，取「第一章」正文 > 200 字。
- 看漫网在线：列表与搜索走 `/api/getsortlist/` 分页正常，详情页 609 话目录按阅读顺序内联，阅读页首图 `HEAD` 校验通过（签名链接需回源）。

## 十二、本轮实测的「不可接入」清单（2026-11-06）

用户要求「把推荐但未实现的站点都实现」，以下站点逐个实测后确认当前不可做或不宜做，证据如下，
后续复盘不必重复踩：

| 站点 | 实测证据 | 阻塞类型 |
| :--- | :--- | :--- |
| vomic漫 `vomicmh.com` | 首页 107 KB 有 `/detail/{id}` 链接，但详情页 47 KB 内既无章节链接也无 `__NEXT_DATA__`/`__NUXT__`/`window.__INITIAL_STATE__` | SPA，章节靠客户端接口 |
| 爱看机器人 `ikanbot.com` | `/play/{id}` 14 KB，`m3u8` 出现 0 次、`<iframe>` 0 个；播放器由 `/resources/play_new.js?v=1.64` + video.js 现场配置 | SPA，取流地址靠 JS/接口 |
| 漫画人 `manhuaren.com` | 首页 11 KB 纯壳，无 `__NEXT_DATA__`/`/api/` 线索 | SPA + 移动接口签名 |
| MangaBZ `mangabz.com` | 首页 43 KB 有 `/1032bz/` 这类漫画页，但图片地址由页面内 JS 变量拼装 | JS 拼装图片地址 |
| 8comic 無限動漫 `8comic.com` | 同 MangaBZ 一类：图片地址在 JS 里解包 | JS 解包 |
| 极速漫画 `1kkk.com` / 动漫屋 `dm5.com` | 老参数体系 + 图片混淆（站点自己也要执行 JS） | JS 注入 + 图片混淆 |
| 七猫中文网 `qimao.com` | 详情页 60 KB 内章节链接 0 个（只有 `/shuku/a-a-a-a-a-a-a-click-1/` 这类装饰链接） | 目录走签名接口 |
| 刺猬猫 `ciweimao.com` | 200 / 135 KB，登录与正文混排 | 登录混合 |
| 追书神器 `zhuishushenqi.com` | 详情 548 KB 内联 7088 章，但正文页 166 秒后 `ChunkedEncodingError`（站点对该书断流限速） | 站点限速 |
| 92漫画 `92mh.com` | 2026-11-06 复测直连恢复正常（列表/详情/阅读页均 200），此前的 TLS 握手超时不再复现 | 链路波动，非站点限制 |
| 52书库 / 101言情 | 直连不可达，需代理 | 网络受限，需代理 |
| SF轻小说 VIP 章 | 免费章做完了；VIP 章解析不到正文时返回提示页 | 付费墙（不绕过） |

**结论**：能靠纯 SSR 或公开 JSON 接的中文漫画/小说/视频源，到本轮基本吃干；
再往下要么逆向签名接口（七猫/漫画人/ikanbot），要么研究 JS 混淆（dm5/1kkk/8comic/MangaBZ），
要么依赖代理（92漫/52书库/101言情），都不适合在没有登录态的前提下塞进解析器。

## 十三、候选站点全量状态表（收口，2026-11-06）

第一节至第三节列出的每个候选站点在此逐条收口：**已内置**（附解析器文件）、**放弃**（结构性不可行，附实测证据）、
**暂缓**（结构可做但本机当前无代理、无法做在线验证）、**不适用/不接入**（模型不匹配或解除 DRM/付费超出边界）。
此表与前面章节的证据一一对应，作为「推荐站点是否都已落地」的核对清单。

| 站点 | 类型 | 状态 | 证据 / 解析器 |
| :--- | :---: | :--- | :--- |
| 明日方舟漫画 `terra-historicus.hypergryph.com` | 漫画 | ✅ 已内置 | `TerraHistoricus.kt`：鹰角官方漫画站，纯 JSON 接口（列表/更新/详情/阅读/取图五个端点），两个频道一次给全，图片 CDN 地址带时效签名由取图接口现取（第十五节） |
| 腾讯动漫 `ac.qq.com` | 漫画 | ✅ 已内置 | `TencentComicsParser.kt`；只解析公开免费章，`canRead=false` 显式报错（第八节） |
| 咚漫 `dongmanmanhua.cn` | 漫画 | ✅ 已内置 | `Dongmanmanhua.kt`；`#_imageList`、站内无分页参数按题材段切片（第八节） |
| 拷贝漫画 / 漫画柜 / 包子漫画 / 优酷漫画 / 再漫画 / Komiic / 滴答六漫画 / 瓜子漫画 | 漫画 | ✅ 已内置 | 仓库原有 `COPYMANGA`/`MANHUAGUI`/`BAOZIMH`/`MHKAMI`/`ZAIMANHUA`/`KOMIIC`/`DDMANHUA`/`GUAZI` |
| 漫客栈 `mkzhan.com` | 漫画 | ✅ 已内置 | `Mkzhan.kt`：`/list/{分类}?theme_id=N&page=N`、目录 1000+ 话内联、图片去签名后缀即原图（第十节） |
| 看漫网 `kanman.com` | 漫画 | ✅ 已内置 | `Kanman.kt`：PC 分页已坏，改走 `/api/getsortlist/`；阅读页签名链接需回源（第十节） |
| 漫画社 `311s.com` | 漫画 | ✅ 已内置 | `Manhuashe.kt`：搜索是路径式 `/search/{kw}`，目录几百话内联（第十节） |
| 快看漫画 `kuaikanmanhua.com` | 漫画 | ❌ 放弃 | 详情页 `__NUXT__` 里 `topicInfo:{}`/`comics:[]`，`api.kkmh.com/v1/topics/{id}` 需签名回 `code:3504`（第八节） |
| 极速漫画 `1kkk.com` / 动漫屋 `dm5.com` | 漫画 | ❌ 放弃 | 首页 392/403 KB 但静态作品链接 0 个（脚本注入）+ 图片混淆（第十二节） |
| 漫画人 `manhuaren.com` | 漫画 | ❌ 放弃 | 首页 12 KB 应用壳，章节走 `mangaapi.manhuaren.com` 移动 API（第十二节） |
| MangaBZ `mangabz.com` | 漫画 | ❌ 放弃 | 图片地址由页面 JS 变量拼装（第十二节） |
| 8comic 無限動漫 `8comic.com` | 漫画 | ❌ 放弃 | 阅读页需 JS 解包（第十二节） |
| vomic漫 `vomicmh.com` | 漫画 | ❌ 放弃 | 详情页 47 KB 既无章节链接也无 `__NEXT_DATA__`/`__NUXT__`/`window.__INITIAL_STATE__`（第十二节） |
| 哔哩哔哩漫画 `manga.bilibili.com` | 漫画 | ❌ 不接入 | 以付费/漫画券为主且需登录态；2026-11-06 复核首页 471 KB 里 `券`/`VIP`/`登录` 均为付费链路的必然入口（合规边界） |
| 92漫画 `92mh.com` | 漫画 | ✅ 已内置 | `Jiuermanhua.kt`：SinMH 模板，36 部/页；排序在路径里（人气是 `/list/-click/`）、筛选要带尾 `-`、搜索参数是 `keywords`；只有「类型」组是顶层标签；图片域名取自 `/js/config.js`（第十六节） |
| 喜漫 `favcomic.com` / 漫画160 `mh160mh.com` / 喵趣 `miaoqumh.org` / 漫画屋 `mhua5.com` | 漫画 | ⏸ 暂缓（网络/模板不同） | 2026-11-06 复核：favcomic 连接重置、miaoqumh 与 mhua5 详情页 TLS 超时、mh160 是另一套 `template/skin1` 模板（非漫画社的 `/comic_{id}.html`） |
| 顶通 Toptoon `toptoon.net` | 漫画 | ⏸ 暂缓（网络） | 2026-11-06 直连首页与 `/webtoon/` 均 20 s 超时（此前经代理 200 / 73 KB），本机无代理 |
| 香香腐宅 `boylove.cc` / 鸟鸟韩漫 `nnhanman.xyz` / 漫蛙2 `manwa2.com` | 漫画 | ❌ 不接入 | 成人向站点：2026-11-06 复核 boylove 超时、nnhanman `Connection reset by peer`、manwa2 TLS 超时；即便可达也属 18+ 内容，需连同分级标记一起评估，不在本轮范围 |
| SF轻小说 `book.sfacg.com` | 小说 | ✅ 已内置 | `Sfacg.kt`：**桌面 UA 是硬要求**（移动 UA 下发 12 KB 精简页且目录只剩章节名），分页参数 `PageIndex`（第十节） |
| 落秋 / 全本 / 全本书屋 / 笔趣阁55 / 书海阁 / 笔趣阁 | 小说 | ✅ 已内置 | `Luoqiu.kt`/`Quanben.kt`/`Qb5.kt`/`Biquge55.kt`/`Shuhaige.kt` + 仓库原有 `BIQUGE`（第八、九、十节） |
| 番茄小说 `fanqienovel.com` | 小说 | ❌ 放弃 | 正文走 `api5-normal-lf.fqnovel.com` + 字体混淆：2026-11-06 复核首页 160 KB 内含 6 处 `@font-face`（第八、十二节） |
| 七猫中文网 `qimao.com` | 小说 | ❌ 放弃 | 详情页章节链接 0 个；2026-11-06 复核首页 294 KB 已整体转 `__NUXT__` 外壳，目录走签名接口（第十二节） |
| 刺猬猫 `ciweimao.com` | 小说 | ❌ 放弃 | 2026-11-06 复核：详情页 113 KB / 21 章可读，但章节页正文容器不存在，页面加载 `enjs.min.js` + `myEncrytExtend-min.js` 做客户端解密，需登录（第十二节） |
| 晋江 / 起点 / 纵横 / 17K / 塔读 | 小说 | ❌ 放弃 | 2026-11-06 复核：起点 202 空响应、纵横 402 KB 但 `__NUXT__` 壳、**17K 列表 147 KB / 586 章可解析但章节页统一 `405`**、晋江 GBK 且以付费章为主、塔读 44 KB SPA 壳 |
| 52书库 `52shuku.net` | 小说 | ⏸ 暂缓（网络不稳） | 2026-11-06 复核首字节 200 / 25 KB 但 body 读取 `ReadTimeout`；此前代理 200 / 21 KB，分类是拼音目录 |
| 101言情 `txtxs101.com` | 小说 | ⏸ 暂缓（网络） | 本机直连 `Connection reset by peer`（http/https 均被重置），经代理 200 / 25 KB；结构是 `/id/{id}.html` + `/class/{id}_{页}.html` |
| 乐文小说 `lewen.org` | 小说 | ❌ 放弃 | 直连与代理均 403；且详情页只给最近 112 章、无全本目录页、搜索 500（第十节） |
| 阅友 `sma.yueyouxs.com` / 随心看 `m.suixkan.com` | 小说 | ❌ 放弃 | 首页 61–73 KB 有书目正文但 `<a>` 标签数为 0，全靠 JS 绑定点击（第十节） |
| 追书神器 `zhuishushenqi.com` | 小说 | ⏸ 暂缓 | 详情页 548 KB 内联 7088 章，但正文页 166 s 后 `ChunkedEncodingError`（站点限速，在线验证不可靠） |
| 动漫456 / 风车动漫 / 「樱花动漫」站群 | 视频 | ✅ 已内置 | `Dm456.kt`/`Fengche.kt`/`Yhdm.kt`（第八、九、十节） |
| 黄果短剧 `huangguoai.com` | 视频 | ✅ 已内置 | 仓库原有 `HUANGGUO`，含标签筛选与分页修复 |
| AGE动漫 `agedm.io` | 视频 | ✅ 已内置（播放链路已修） | 「解析 iframe + m3u8 直链」链路已按 2026-11-06 实测实现（见第十六节末条） |
| 红果短剧 `hongguoduanju.com` | 视频 | ❌ 放弃 | 播放页是系列级 `/player/{series_id}`，JSON-LD 里 168 个 episode 指向同一 URL，串流走字节签名接口（第八节） |
| 樱花动漫（老站）`yhdmz.org` | 视频 | ❌ 放弃 | `www` 正文 0 KB（`Loading...` 壳），`m.` 302 到博彩落地页；2026-11-06 复核仍超时 |
| 蜜柑计划 `mikanani.me` / 动漫花园 `share.dmhy.org` | 视频 | ❌ 不适用 | BT 种子站：条目是 torrent/magnet 而非「章节=页面」，与 Content/Chapter/Page 模型不匹配（第十二节） |
| 爱剧迷 Gimy `aigm.tv` | 视频 | ❌ 放弃 | 播放页 `/play/{混淆 token}` 需逆向；2026-11-06 复核 TLS 握手超时 |
| 爱看机器人 `ikanbot.com` | 视频 | ❌ 放弃 | 播放页 14 KB 无 m3u8、无 iframe，播放器由 `play_new.js` 现场配置（第十二节） |
| 低端影视 `ddys.app` | 视频 | ❌ 不适用 | 资源索引站（磁力/网盘），没有逐页播放模型 |
| 欧乐影院 `olevod.com` | 视频 | ❌ 放弃 | 客户端路由 SPA，需接口逆向；2026-11-06 复核超时 |
| Libvio `libvio.la` | 视频 | ❌ 放弃 | 正文即「域名暂时停止使用通知」，站点已停用 |
| 稀饭动漫 / NyaFun / 嘶哩嘶哩 | 视频 | ❌ 放弃 | `xifanacg.com` 不可达；2026-11-06 复核 `nyafun.net` 5 KB 跳转页、`silisili.cc` 21 KB 已变导航站（0 个作品链接） |
| 正版长视频（bilibili / 爱奇艺 / 腾讯视频 / 优酷 / 芒果） | 视频 | ❌ 不接入 | 付费 + DRM，不做绕过 |

### 剩余待办（不阻塞本清单收口）

1. ~~**AGE动漫播放链路修复**（`AGEDM`）~~：2026-11-06 已完成，真实链路是「第三方解析 iframe → `…/m3u8/` 响应里的
   `var Vurl` 直链」，不是 `/v2/captcha`（页面里已经没有该接口）；`/vip/` 线路（西瓜）由现场 JS 解密，客户端不可播，
   解析器直接抛「请切换线路」。详见第十六节。
2. **需要代理才能验证的站点**：92漫画、52书库、101言情、顶通 Toptoon、喜漫/漫画160/喵趣/漫画屋，
   以及第五节上游清单里本机不可达的 `comicabc`（`articles.onemoreplace.tw` 超时）、`tongli`（東立，需 Firebase
   登录态）、`utifa`（域名已变更）、`zazhimi`（站点只有占位页）、`hanabimanga`（Supabase REST 308 → TLS 超时）、
   `noyacg`（SPA 壳，需要抓接口）、`zerobyw`。结构线索已记录在册（52书库拼音分类、101言情 `/id/{id}.html`），
   有可用代理时可直接进入实现。
3. **成人向站点**（香香腐宅/鸟鸟韩漫/漫蛙2）：需与 18+ 分级标记一起评估，单独一轮处理。

## 十四、2026-11-06 复核记录（本机无代理，直连 20–30 s 超时）

本轮为「剩余未内置站点」做了一次全量直连复核，用于确认前面的放弃/暂缓结论没有被站点改版翻盘：

| 站点 | 复核结果 |
| :--- | :--- |
| `17k.com` | 首页 221 KB / 1187 条书目链接、`/list/3631088.html` 147 KB 内联 586 章均可解析，但**三本不同作品的章节页全部 `405 Not Allowed`**（带 session + Referer 也一样）→ 正文无法验证 |
| `ciweimao.com` | 首页 111 KB / 66 部书、详情页 113 KB / 21 章正常，章节页 59 KB 内无正文容器，依赖 `enjs.min.js`/`myEncrytExtend-min.js` 客户端解密 |
| `fanqienovel.com` | 160 KB，6 处 `@font-face`（字体混淆确认） |
| `qimao.com` | 294 KB 且出现 `__NUXT__`（整站转 Nuxt 壳） |
| `52shuku.net` | 首包 200 / 25 KB，body `ReadTimeout`（链路不稳） |
| `agedm.io` | 首页 133 KB / 166 部、详情 797 KB / 9332 集，播放页无 m3u8 直链（见待办 1） |
| `ddys.app` 73 KB、`nyafun.net` 5 KB、`silisili.cc` 21 KB | `ddys.app` 0 条作品链接（SPA）；`nyafun.net` 为跳转页；`silisili.cc` 72 个外链全是导航 |
| `toptoon.net`、`boylove.cc`、`mikanani.me`、`share.dmhy.org`、`aigm.tv`、`olevod.com`、`libvio.la`、`yhdmz.org`、`txtxs101.com` | 全部直连超时或被重置（此前经代理可达，结论不变）；`92mh.com` 2026-11-06 已恢复直连并接入，不在此列 |
| `mh160mh.com` 62 KB / `mhua5.com` 88 KB | 均为另一套模板（`template/skin1`、ThinkPHP `/index.php/comic/{slug}`），不是漫画社模板；详情页复核时 TLS 超时 |
| `terra-historicus.hypergryph.com` | **可达且已实现**：`/api/comic?topicKey=terra-historicus` 22 部、`talos-ii-historicus` 6 部，详情/阅读/取图均为 JSON，见第十五节 |
| `noymanga.com`（上游 `noyacg` 新域名）/ `noyacg.com` | 前者 2 KB SPA 壳，后者 43 KB 但 `<a>` 标签 0 个、`/rest/v1/comics` 返回的还是同一个 HTML 壳 → 需要抓接口 |
| `web.hanabimanga.com` | Supabase REST `/rest/v1/comics` 回 **308 Permanent Redirect**，跳转目标 `hanabimanga.com` 直连 TLS 握手超时 → 本机不可达 |
| `android2026.zazhimi.net`（上游 `zazhimi`） | 200 但正文只是 nginx 默认页「服务器运作正常」（0 KB），站点当前没有内容 |
| `api.tongli.tw`（東立）/ `articles.onemoreplace.tw`（`comicabc` 章节站） | 直连 TLS 握手超时；`api.tongli.tw` 探到的路径全 404（需要 Firebase 登录态 + 未知路由），本机无法验证 |
| `utifa.com` / `www.utifa.com` | DNS 解析失败（域名已变更），上游源走运行时配置的 `apiUrl`，需要代理才能确认现域名 |

## 十五、本轮实现（2026-11-06）：明日方舟漫画 `terra-historicus.hypergryph.com`

上游 Keiyoushi 有 `terrahistoricus` 源，仓库此前没有；本轮实测该站**只提供 JSON 接口**，没有可抓的 HTML 列表，
因此实现方式与前面所有 HTML 源都不同，单独记录在此。

### 协议（全部实测）

| 用途 | 端点 | 关键字段 |
| :--- | :--- | :--- |
| 频道作品列表 | `GET /api/comic?topicKey={频道}` | `cid`（作品 id）、`title`、`subtitle`、`cover`、`authors[]` |
| 频道最近更新 | `GET /api/recentUpdate?topicKey={频道}` | `comicCid`、`title`、`coverUrl`、`subtitle`、`updateTime`（秒） |
| 作品详情 | `GET /api/comic/{cid}` | `introduction`、`keywords[]`、`authors[]`、`episodes[]` |
| 话页数 | `GET /api/comic/{cid}/episode/{eid}` | `pageInfos[]`（含 `width`/`height`/`doublePage`），长度即页数 |
| 取图 | `GET /api/comic/{cid}/episode/{eid}/page?pageNum={n}` | `data.url`（`res01.hycdn.cn` 带时效签名） |

频道只有两个：`terra-historicus`（泰拉记事，22 部）与 `talos-ii-historicus`（塔罗斯II，6 部），
所有接口响应都是 `{"code":0,"data":…,"msg":""}`，`code != 0` 即站点侧异常。

### 三个必须记住的坑

1. **作品 id 与话 id 共用 `cid` 字段**：详情里 `episodes[].cid` 是话 id，不是作品 id；拼取图地址时两者都要带上，
   所以章节 URL 用 `/ {频道}/comic/{作品id}/{话id}` 三段式，解析时按正则取第 2、3 段。
2. **接口给的是新话在前**：`episodes[]` 首元素是最新一话（实测 `02 下篇` 在 `01 上篇` 前面），
   必须反转后再编号，否则阅读顺序整个倒过来。
3. **图片地址是时效签名**：`data.url` 里的目录段（如 `6006834dd907aace507228f3e59ae76b/6AED519F`）会变，
   不能在 `getPages` 里一次性取全部图片地址，只能把「取图接口地址」存进 `ContentPage.url`，
   在 `getPageUrl` 里现取现用（上游源也是这么做的，还额外加了 403 重取拦截器）。

另外站点没有分页/排序参数：两个频道各自一次给全，因此把「页码」当作频道位（第 1 页 = 泰拉记事，
第 2 页 = 塔罗斯II，第 3 页起为空且不发请求），并提供「频道」标签让用户直接选；搜索按标题在本地过滤
（上游源同样如此），不伪造服务端搜索。

### 验证

```bash
# 离线夹具（列表 22→3、塔罗斯II 6→2、更新 4→3、61 页→4 页，均取自真实响应）
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.TerraHistoricusTest" --no-daemon
# 14 个用例：能力声明/频道标签/两频道列表/越界页不发请求/标签覆盖页码/更新顺序/本地搜索/
#            章节反转与编号/页数映射/取图解析/空页报错/非零 code 报错/外来 URL 报错

# 在线验证（两个频道 + 更新顺序 + 标签筛选 + 本地搜索 + 首尾图片 HEAD 200 + image/*）
TERRA_HISTORICUS_INTEGRATION_TEST=1 ./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.TerraHistoricusIntegrationTest" --no-daemon
```

## 十六、本轮实现（2026-11-06）：九二漫画 `www.92mh.com`

上游 Keiyoushi 有 `jiuermanhua` 源，仓库此前没有。本站是 SinMH 模板的商店化改造（首页、详情、阅读页全 SSR），
与漫画社/漫客栈那类 ThinkPHP 模板完全不同，协议单记一节。

### 协议（全部实测）

| 用途 | 端点 | 关键点 |
| :--- | :--- | :--- |
| 默认列表 | `GET /list/?page=N` | 36 部/页，按发布倒序 |
| 更新 / 人气 | `GET /list/update/?page=N`、`GET /list/-click/?page=N` | **排序是路径段**；人气那段必须带减号前缀 |
| 分类筛选 | `GET /list/{标签1}-{标签2}-/?page=N` | 末尾 `-` 不能省，否则标签被静默忽略 |
| 筛选 + 排序 | `GET /list/shaonian-/update/?page=N`、`/list/shaonian-/click/?page=N` | 相当于「标签段 + `/排序/`」 |
| 搜索 | `GET /search/?keywords={kw}&page=N` | 参数名是 `keywords`；用 `keyword=` 会 302 回首页 |
| 详情 | `GET /manhua/{漫画id}/` | `h1`、内联 `var pageImage`、`ul.comic_deCon_liO li`、`p.comic_deCon_d`、若干段 `ul#chapter-list-N li a` |
| 阅读页 | `GET /manhua/{漫画id}/{章节id}.html` | 内联 `var chapterImages = [...]`（斜杠转义成 `\/`） |
| 图片域名 | `GET /js/config.js` | `resHost[].domain[0]`，会变，取一次缓存；站点对连续请求会 403，解析器隔 1.5 s 重试一次 |

### 五个必须记住的坑

1. **筛选必须有尾 `-`**：`/list/shaonian/` 与 `/list/shaonian-/` 返回的是同一批内容，但后者才带分页；
   真正让标签生效的是拼成 `/list/{tag}-/`，直接 `/list/huanlexiang-/` 是空列表——只有「类型」组是顶层标签，
   剧情/地区/受众组必须带分类前缀（`/list/shaonian-danmei/`），所以本仓库只暴露「类型」四个标签。
2. **搜索页是另一套卡片**：列表页是 `a.comic_img + h3 > a`，搜索结果却是 `a.image-link + p > a`、作者在 `p.auth`
   （且多个作者连写无分隔），解析器两种结构都认，否则搜索永远返回 0 条。
3. **章节标题不保证是数字**：`第01话`、`第4.2话`、`9话番外`、`单行本01`、`元旦祝贺` 混排；
   能解析出数字就用数字，解析不出按顺序补号，并做单调兜底（「03话番外」排在「第04话」之后不能把序号拉回去）。
4. **长篇的目录被切成多段**：斗破苍穹（5344）一个页面里有三段互不重叠的列表——
   `chapter-list-1` 是最新 30 话、`chapter-list-3` 是一条停更公告、`chapter-list-9` 是开头 701 话（第1话起）；
   只认 `chapter-list-1` 会拿到 30 话而不是 732 话。解析器合并所有 `ul[id^=chapter-list]`、按章节 id 升序重排，
   实测拿全 732 话且首章是「第1话 陨落的天才（上）」、末条是「停更公告」，序号单调递增。
   短篇只有一段，此时保持站点原本的正序，不再额外排序（站点自有顺序里番外/单行本的 id 并不递增）。
5. **部分作品只给一话**：`/manhua/7702/`（第93话）只有 1 条章节链接，`/list/`、`/all/`、`?page=2` 都一样，
   属站点自身内容；解析器照实反映，不做补全，也不因此报错。

### 验证

```bash
# 离线夹具（列表 3 部、搜索 1 部、详情 7 话含番外/单行本、多段目录 9 条、阅读页 3 图，均取自真实响应）
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.JiuermanhuaTest" --no-daemon
# 11 个用例：能力声明/列表卡片/搜索卡片/分类分组/详情元数据/章节编号单调/多段目录合并重排/图片拼接/空章节/URL 规则

# 在线验证（列表翻页 + 详情 + 237 页阅读页首尾图片 HEAD 200 + image/* + 搜索 + 分类 + 三种排序互不相同
#           + 带标签的三种排序互不相同 + 长篇 5344 拿全 732 话且序号单调）
JIUERMANHUA_INTEGRATION_TEST=1 ./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.JiuermanhuaIntegrationTest" --no-daemon
```

## 十七、2026-11-06 修复：AGE动漫 `agedm.io` 播放链路

第十节把 AGE 标成「线路占位 + `/v2/captcha` 接口取流」，本次复核发现既不是线路占位、也没有 `/v2/captcha`
（全站响应里都不再出现这个接口）。真实结构如下：

### 协议（真实响应）

1. 播放页 `/play/{id}/{sid}/{ep}` 服务端只渲染一个 iframe：`<iframe id="iframeForVideo" src="https://jx.wuzhoupai.com:8443/…">`，
   线路编号 `sid` 直接决定解析端点：

   | 线路（sid） | iframe 端点 | 响应里的 `var Vurl` | 客户端可播 |
   |---|---|---|---|
   | 西瓜（1） | `/vip/?url=age_…` | 96 位十六进制密文 | ❌ 需 `global.min.js` 现场解密，且该 CDN 域已无法解析 |
   | 非凡（2） | `/m3u8/?url=age_…` | `https://vip.ffzy-plays.com/…/index.m3u8` | ✅ |
   | 暴风（3） | `/m3u8/?url=age_…` | `https://v.fengbao14.com/…/index.m3u8` | ✅ |
   | 无尽（4） | `/m3u8/?url=age_…` | `https://v6.ppqrrs.com/…/index.m3u8` | ✅（本机该域 DNS 被污染到 1.1.1.1，TLS 校验失败） |
   | 计算云（5） | `/m3u8/?url=age_…` | `https://v.lzcdn31.com/…/index.m3u8` | ✅ |

2. `url=age_…` 是站点给解析服务的加密封装，客户端不需要解它——只要照抄 iframe `src` 去取响应即可。
3. 这些 m3u8 图床**对 HEAD 一律 502、对 GET 返回 200**（实测四台主机都一样），在线校验必须用 GET 读 `#EXTM3U`。

### 解析器改动（`Age.kt`）

- `getPages`：取 `#iframeForVideo` → `resolveStreamUrl`。原来是 `try { … } catch { iframe }` 兜底，
  把解析失败静默成「用 iframe 地址当视频地址」，现在失败就抛带上下文的 `ParseException`。
- 直链提取改成 m3u8 正则扫解析响应，覆盖 `/m3u8/` 线路；旧 `api.php`（url/t/key/act/play）链路保留兜底。
- **只认 m3u8，不认泛 mp4**：`/vip/` 播放器配置里有 `Kpadmp4: "…/Play/adposter.mp4"` 这类**广告**位，
  宽松的 `.mp4` 正则会把广告当正片（第一版就踩了这个坑，在线用例报「西瓜线路不是 m3u8 直链: adposter.mp4」）。
  真正的 mp4 直链只会出现在 `var Vurl` 里，由 `parseIframePayload` 的 Direct 分支处理。
- `var Vurl` 只有在看起来是 URL 时才当直链——`/vip/` 线路里它是 96 位十六进制密文，之前会被原样当播放地址返回。
- 失败一律抛带上下文的 `ParseException`：先取完解析响应再判断，若仍无直链且端点是 `/vip/` 就提示
  「请切换到非凡/暴风/无尽/计算云」，否则报「解析线路没有返回可播放地址」；不再把无名异常吞成 iframe 地址。
- 暴露 `internal parseStreamUrl` / `internal isJsOnlyIframe` 供离线夹具测试。

### 验证

```bash
# 离线夹具（真实响应精简：播放页 5 线路×3 集、/m3u8/ 与 /vip/ 两种解析响应、详情页）
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.AgeTest" --no-daemon        # 8/8（含广告 mp4 反例）

# 在线验证（列表/详情 → 逐线路取第一集 → GET 读 #EXTM3U；西瓜线路断言报错文案）
AGEDM_INTEGRATION_TEST=1 ./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.AgeIntegrationTest" --no-daemon
```

在线实测输出（2026-11-06，本机直连）：`黑化吧！圣女大人第二季 chapters=30`，五条线路里
非凡 / 暴风 / 计算云 三条 `200 + #EXTM3U`，西瓜按预期报「请切换线路」，无尽因 CDN 域名 DNS 污染吞掉 SSL 握手失败。

## 十八、本轮实现（2026-11-06）：电影88 `dianying88.net`

### 起点：同引擎的 `jihai88.com` 全是正版跳转

先前按「和 YHDM 同模板」的思路探过 `jihai88.com`（卡片也是 `mcard`、数据也在 RSC 里），但抽 12 部
电视剧，详情页线路清一色是 `mgtv`/`qiyi`/`qq`/`youku`，episode `url` 直接指向平台页面，
没有任何可播的视频流。换到同引擎的 `dianying88.net` 再抽 12 部：8 部带 `bfzym3u8`（暴风线路）HLS 直链。
**同一套前端模板，正片有没有免费线路是站点按片配置的**，不能由域名/模板推断。

### 协议（全部实测）

| 环节 | 地址 | 要点 |
| --- | --- | --- |
| 列表 | `/type/{20,37,43,45,47}?pg=N`、`/latest?pg=N`、`/ranking?pg=N` | 20 部/页；`?pg=2` 换一批（实测 overlap=0）。`order=`/`sort=` 等排序参数一律被忽略 |
| 搜索 | `/search?wd={关键词}` | 表单字段是 `wd`；用 `q` 只返回空结果页。结果无分页 |
| 详情 | `/vod/{id}` | 元数据在 `ld+json` 的 `TVSeries`（`name`/`description`/`image`/`genre`/`countryOfOrigin`/`director`/`numberOfEpisodes`） |
| 线路 | 详情页 `section[aria-labelledby^=source-]` | `h2` 是线路名（爱奇艺/芒果TV/暴风线路），集链接 `/play/{id}?s={线路}&e={集}`，`s` 即线路下标 |
| 播放 | 详情页 RSC flight | `"source":{"key":"bfzym3u8","label":"暴风线路","episodes":[{"label":"第1集","url":"https://v.fengbao14.com/…/index.m3u8"}]}` |

卡片（`article.mcard`）：标题 `span.mcard__title`、标签 `span.mcard__tag`（类型/年份/地区）、
状态 `span.mcard__meta`（「更新：完结」「更新至第 N 集」）、封面在 `img[srcSet]` 的
`/_next/image?url=%2Fmedia%2F….webp&w=…` 里——取 `url` 参数 URL 解码后拼域名即可直取。

### 四个必须记住的坑

1. **播放页没有播放地址**。`/play/{id}?s=&e=` 的 SSR HTML 和 RSC 里都没有 m3u8（播放器另行取数），
   直链只在**详情页** RSC 的 `"source"` 结构里。所以 `getPages` 必须回 `/vod/{id}` 再取一次。
   这和 YHDM（播放页内联 m3u8）正好相反，照抄 YHDM 会 100% 拿不到地址。
2. **正版线路 ≠ 免费线路**。key 以 `m3u8` 结尾（`bfzym3u8`）才是站点自建 HLS；`mgtv`/`qiyi`/`qq`/`youku`
   的 episode `url` 是平台网页。把它们当线路列出来，用户点播就是「播放失败」。做法：章节只保留免费线路；
   整部片都没有免费线路时，放一条 `仅正版跳转：芒果TV、爱奇艺` 的说明性章节，点播时抛
   「该片只提供正版平台跳转（url），本站没有免费播放线路」——既不伪造地址，也不让用户以为解析坏了。
3. **`s` 的语义**。它是**全部线路**的下标（含正版线路），不是「免费线路里的第几条」。`section` 的
   `id="source-N"` 也不是 `s`（有片是 `source-2` 而 `s=0`），必须读集链接里的 `?s=`。章节 url 里编码
   真实 `s`，`getPages` 才能回指到正确线路。
4. RSC 数据在页面里出现多份（HTML 转义版 `\"` 与 flight 版），反转义后按 `key` 去重；否则同一条线路
   会因为重复匹配而让集数翻倍。

### 验证

```bash
# 离线夹具（真实响应精简：列表 3 卡片、搜索 2 卡片、免费+正版混合详情、仅正版详情）
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.Dianying88Test" --no-daemon   # 11/11

# 在线验证（列表翻页不重叠 + 搜索 + 免费线路 GET 读 #EXTM3U + 正版线路报错文案）
DIANYING88_INTEGRATION_TEST=1 ./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.Dianying88IntegrationTest" --no-daemon
```

在线实测输出（2026-11-06，经 7890 代理）：
`list p1=20 p2=20 overlap=0`；`search='罗小黑' hits=8`；`灿烂如野 chapters=8 branches=[暴风线路]`，
第 1、2 集均 `GET 200 + #EXTM3U`（`v.fengbao14.com/…/index.m3u8`）；正版线路按预期报
「该片只提供正版平台跳转…」。注意这些 HLS 图床对 HEAD 返回 5xx，在线校验必须用 GET。

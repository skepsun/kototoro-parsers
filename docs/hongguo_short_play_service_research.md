# 红果短剧接入调研：直连不可行，改用 hongguo-web 网关（2026-11-06）

结论：**直连不可行，但接自建网关可行**。红果短剧（字节番茄系：真人短剧 / 漫剧 / AI 短剧）的
数据与播放都在 App 侧做了签名与加密，纯 Kotlin 解析器无法稳定复刻；而开源网关
[hongguo-web](https://github.com/sunnysky123/hongguo-web)（v1.2.2，Node 18+、零第三方依赖）
把「签名 → 取流 → spade 解密 → 剥 CENC 信令 → 按 Range 输出 mp4」整条链路做完，对外只有一组
普通 JSON 接口。因此本仓库新增的 `HongguoDrama` 解析器**接的是这个网关**，不是红果 App。

本文档的接口结论全部在本机实例（`PORT=8123`，2026-11-06）实测复核。

## 结论速览

| 项 | 实测结果 | 影响 |
|---|---|---|
| 直连 App 接口 | 需 6 个签名头（`x-gorgon` / `x-khronos` / `x-ladon` / `x-argus` / `x-helios` / `x-medusa`）+ `a_bogus`，搜索另需 `device_id` | ❌ 不在解析器里复刻签名 |
| 播放地址 | 上游只给分轨加密 mp4（`spade_a` 解 content key + CENC 信令）；新分集仅 ByteVC2 | ❌ 客户端解不了 |
| 网关接口鉴权 | 全部数据接口需 `x-api-key`；`/api-key` 免鉴权自动签发（实测无 key → 401） | ✅ 解析器首次请求自动取密钥 |
| 列表 | `/browse` 真分页（`offset` + `session_id` 游标 + `next_offset`） | ✅ `PagedContentParser` |
| 搜索 | `/search?q=` 可用（`魔神人生` → 3 条，含 100 集全集） | ✅ 真搜索 |
| 播放 | `/stream?vid=` 服务端解密，`Range` → `206 video/mp4`（实测块 1 MiB / 全片 39.9 MB） | ✅ 直链交给播放器 |
| 体裁 | `short_play` / `comic_series` / `ai_series` 三个 `genre` | ✅ 只暴露这三种为标签 |
| 上游风控 | 偶发 HTTP 200 + 空 body、同设备重复取流被判重 | ⚠️ 网关内部重试，解析器只做报错提示 |

## 一、为什么不能直连

上游 API 域 `api5-normal-sinfonlinea.fqnovel.com`（App 参数 `aid=8662`、`app_name=novelread`、
`version_code=72232`），要点：

1. **签名**：每个请求带六个签名头，其中 `x-khronos` 是秒级时间戳、`x-ladon` 是它的 BE32 base64、
   `x-argus` 是 LE32 base64，还有设备指纹参与的 `x-gorgon`；搜索接口额外校验 `device_id`。
   网关内是 800+ 行纯 JS 复刻（`server/src/core/pure-sign.js`）。
2. **播放**：接口返回的 mp4 是**分轨加密**的，content key 要从 `spade_a` 字段按字节变换解出
   （`server/src/core/spade.js`），再剥掉 CENC 信令（`senc`/`tenc`/`pssh`）才是可播的普通 mp4。
3. **编码**：较早分集是 `bytevc1`（标准 HEVC，可播）；新上线分集**只有 ByteVC2**（字节私有封装，
   码流本身是标准 H.266/VVC，但样本条目写成 `bvc2`、配置盒写成 `bv2C`），网关会以
   `415 + code=BVC2_ONLY` 拒绝在线播放，只留下载后本地播放的路。

以上任何一条都不是「换个 UA/加点 header」能解决的，且都涉及上游签名/加密算法，不适合塞进解析器。
网关把这一层隔离掉，解析器只需处理 HTTP + JSON。

## 二、网关接口（实测）

启动：解压 Releases 发行包后 `scripts/start.*`，或 `cd server && npm start`；
默认监听 `127.0.0.1:8000`，`BIND_HOST=0.0.0.0` 才能局域网访问（对外暴露务必同时设 `ADMIN_TOKEN`）。

| 用途 | 请求 | 关键返回 |
|---|---|---|
| 密钥 | `GET /api-key`（免鉴权） | `{"api_key": "...", "note": "...", "rate_per_min": 600}` |
| 列表 | `GET /browse?genre=&sort=&limit=&offset=[&session_id=]` | `items[]` + `has_more` + `next_offset` + `session_id` |
| 搜索 | `GET /search?q=&limit=&offset=` | `results[]` + `count` + `has_more` + `next_offset` |
| 详情 | `GET /episodes?series_id=` | `meta{title,abstract,episode_cnt,status,cover,category[]}` + `episodes[{index,vid,duration,cover}]` |
| 直链信息 | `GET /video_url?vid=` | `{url,size,definition,codec,playable}`（`url` 带时效签名，不入库） |
| 播放 | `GET /stream?vid=&quality=best` | `206 video/mp4`，支持 `Range`（`HEAD` 不支持，返回 404） |

实测样本（2026-11-06）：

- `/browse?genre=short_play&sort=hot_score&limit=20&offset=0` → `count=24`、`session_id=site`、
  `next_offset=2`、`has_more=true`（首屏走官网 SSR 快路径，续页沿用同一会话）。
- 列表条目字段：`series_id`、`title`、`cover`、`episode_cnt`、`score`、`play_cnt`、`heat`、
  `tags[]`、`category`、`abstract`、`vid`、`stream_url`、`episodes_url`。
- `/search?q=魔神人生` → `count=3`，首条 `series_id=7474811850085895230`、`episode_cnt=100`、
  `score=8.3`、`vid=7474846201993497624`。
- `/episodes?series_id=7474811850085895230` → `meta.status=完结`、100 条 `episodes`（`index` 1..100）。
- `/stream?vid=7474846201993497624` + `Range: bytes=0-1048575` → `206`、
  `Content-Range: bytes 0-1048575/39968709`、`Content-Type: video/mp4`。

## 三、解析器映射（`HongguoDrama`）

| 契约 | 实现 |
|---|---|
| 来源 | `ContentType.VIDEO`、`ContentRating.SAFE`、`zh` |
| 服务地址 | `configKeyDomain`，默认 `127.0.0.1:8000`；填了 `http://` / `https://` 就原样用，否则按 `http://` 处理（本机/内网服务通常没证书） |
| 密钥 | `ConfigKey.Text("service_api_key")`；留空时读一次 `/api-key` 并缓存，手填优先 |
| 列表 | `/browse`；`POPULARITY → sort=hot_score`、`UPDATED → sort=online_time` |
| 体裁标签 | 真人短剧 / 漫剧 / AI 短剧 → `genre` 参数 |
| 内容标签 | 详情里的「逆袭」「重生」等没有筛选接口，退化成关键词 `/search`（点了标签列表仍会收窄） |
| 翻页 | 沿用上一页的 `session_id` + `next_offset`，不按 `page*pageSize` 硬推（网关两种游标混用）；`has_more=false` 后不再发请求 |
| 去重 | 网关深池续页会带少量重复条目，按站内 `series_id` 在实例内去重 |
| 详情 | `/episodes` → 章节 `title=第N集`、`url=/video/{vid}`（与网关地址无关） |
| 播放 | 先查 `/video_url`：`playable=false`（ByteVC2）直接抛「只有 ByteVC2 片源」；否则返回 `{base}/stream?vid=&quality=best`，并带 `X-Api-Key` 头 |
| favicon | 网关无站点图标，返回空列表，避免对服务地址发无意义请求 |

## 四、已知限制

1. **必须自建服务**：解析器本身不带任何红果凭据，服务没起就只会得到明确报错
   （「连不上 hongguo-web 服务…」）。同一服务的多个客户端共用一个密钥即可。
2. **ByteVC2 分集播不了**：新分集上游只给 BVC2，在线播放被网关以 415 拦下
   （网关可下载成标准 VVC mp4 后本地播放）；解析器在 `getPages` 前置检查并报原因。
3. **官网快路径池子小**：仅体裁 + 最热时首屏走官网页，翻完自动续接 App 深池；同一榜单
   相邻页可能有少量重复（已做实例内去重）。
4. **上游风控**：偶发 200 + 空 body、同设备短时间重复取流被判重；网关有重试，解析器不做额外兜底。
5. **未暴露 8 维筛选**：网关 `/filters` 支持主题/设定/背景/受众/完结/上新/时长等，
   但解析器只暴露体裁标签，避免把多选 UI 映射到单选的 `theme` 参数。

## 五、部署与设置向导

应用里的设置页只能渲染 `ConfigKey` 的标题，放不下多步说明，所以向导正文在这里：
设置页只留两处最短提示——「视频源域名」的候选值（`127.0.0.1:8000`、`10.0.2.2:8000`）和
「网关 API Key（留空自动获取）」。下面按设置页从上到下的顺序走一遍。

### 步骤 0 · 先决定网关跑在哪

| 场景 | 网关跑在 | 「视频源域名」填 | 备注 |
|---|---|---|---|
| 桌面端，只在本机看 | 同一台电脑 | `127.0.0.1:8000` | 默认值，什么都不用改 |
| 手机 + 电脑同一 WiFi | 电脑 | `192.168.x.x:8000` | 必须让网关监听 `0.0.0.0`，见步骤 3 |
| 手机自己跑（Termux） | 手机 | `127.0.0.1:8000` | 端口照旧，注意 Termux 后台会被回收 |
| Android 模拟器 | 宿主机 | `10.0.2.2:8000` | `10.0.2.2` 是模拟器里指向宿主机的固定别名 |
| 公网 / 反代 | 服务器或 NAS | `https://hg.example.com` | 带协议则原样使用，需自备证书 |

域名按 `HttpUrl` 校验：**只填 `host:port` 也能用**（解析器补 `http://`），
`https://` 开头的完整地址原样使用。

### 步骤 1 · 拿网关

- 不想装 Node：从 [hongguo-web Releases](https://github.com/sunnysky123/hongguo-web/releases)
  下对应平台的发行包，**已内置 Node 运行时**，解压即用。老发行版（glibc 过旧）选带
  `-node24` 的变体包。
- 想改代码：`git clone` 后进 `server/` 跑 `npm start`，需要 Node ≥ 18，**没有任何第三方依赖**。

### 步骤 2 · 启动

| 平台 | 启动 | 停止 |
|---|---|---|
| Windows | 双击 `scripts\start.bat` | 双击 `scripts\stop.bat` |
| Linux / macOS | `bash scripts/start.sh` | `bash scripts/stop.sh` |
| 通用 | `node server/src/main.js` | `node server/src/main.js --stop` |

脚本会在后台轮询 `GET /health`，就绪后自动打开 <http://127.0.0.1:8000/>（想关掉设
`HG_OPEN_BROWSER=0`）。排障常用：`node server/src/main.js --config` 看生效配置、
`--version` 看版本、`--selftest` 跑 173 项自检。

端口被占用时换一个：`PORT=8001 bash scripts/start.sh`（Windows：`set PORT=8001 && scripts\start.bat`），
应用里的地址跟着改成 `:8001`。

### 步骤 3 · 让手机连得上（跨设备才需要）

网关默认只监听 `127.0.0.1`，手机填了也连不上，要放开监听并设管理口令：

```bash
BIND_HOST=0.0.0.0 ADMIN_TOKEN=<自己定一串> bash scripts/start.sh
```

- 查电脑的局域网 IP：macOS `ipconfig getifaddr en0`；Linux `ip route get 1 | awk '{print $7}'`；
  Windows `ipconfig`（看「无线局域网适配器」的 IPv4 地址）。
- 手机浏览器先打开 `http://<电脑IP>:8000/health` 验证，**通了再回应用里填**。
- 系统防火墙要放行该端口；同一 WiFi 下有些路由器还开了「AP 隔离」，会挡掉手机访问。

### 步骤 4 · 在应用里填地址

设置 → 内容源管理 → 红果短剧 → 视频源域名。候选值点一下即填：

- 网关与 App 同机：`127.0.0.1:8000`
- 模拟器连宿主机：`10.0.2.2:8000`
- 跨设备：`192.168.1.20:8000` 这种「电脑 IP + 端口」

> 应用内该页的「在浏览器打开」会按 `https://<域名>` 拼地址，而网关默认是 http；
> 想打开网关自带页面请手动在浏览器输 `http://<域名>/`。

### 步骤 5 · 密钥留空就行

数据接口要带 `X-Api-Key`，但 `/api-key` 本身免鉴权：解析器启动后第一次请求会自己取一次并缓存，
**「网关 API Key」这一栏留空即可**。只有两种情况需要手填：

1. 同一个网关给多个来源/客户端用，想固定同一把密钥；
2. 网关关闭了 `/api-key` 自动签发（此时用 `ADMIN_TOKEN` 调 `/admin/keys` 签发，再粘进来）。

### 步骤 6 · 自检四连（连不上时按顺序打）

```bash
# 1) 服务活着吗
curl -sS http://127.0.0.1:8000/health
# 2) 能拿到密钥吗
curl -sS http://127.0.0.1:8000/api-key
# 3) 数据接口通不通（热播前 2 条）
curl -sS -H "x-api-key: $(curl -sS http://127.0.0.1:8000/api-key | python3 -c 'import json,sys;print(json.load(sys.stdin)["api_key"])')" \
  'http://127.0.0.1:8000/browse?genre=short_play&sort=hot_score&limit=2&offset=0'
# 4) 取流是不是 206 video/*
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -r 0-1023 \
  -H "x-api-key: <上面那把 key>" 'http://127.0.0.1:8000/stream?vid=<任意 vid>'
```

第 3 步返回 `{"items":[...]}` 说明数据和密钥都正常，剩下的问题一定在播放或上游风控上。

### 故障排查

| 应用里看到 | 真实原因 | 处理 |
|---|---|---|
| 「连不上 hongguo-web 服务…」 | 网关没起 / 地址或端口不对 / 跨设备没放开监听 | 步骤 2 + 步骤 6 第 1 步；跨设备回到步骤 3 |
| 手机填 `127.0.0.1` 一直转圈 | `127.0.0.1` 指手机自己 | 换成电脑的局域网 IP |
| 401 / 403 | 密钥没取到，或手填的与网关不一致 | 把「网关 API Key」清空，让解析器自动取 |
| 「分集只有 ByteVC2…」415 | 该集上游只给 ByteVC2，网关不转码 | 已知限制，改用网关的下载功能存成本地 mp4 |
| 列表空 / 偶发失败 | 上游风控：200 + 空 body、重复取流被判重 | 等几秒重试；同一集别连续狂点 |
| `GLIBC_2.28 not found`、`cannot execute binary file` | 发行包内置的 Node 与系统 glibc 不匹配 | 换 `-node24` 变体包，或源码 checkout 用系统 Node |
| 端口占用 | 8000 被别的程序占了 | `PORT=8001` 重启，应用里地址同步改 |

### 步骤 7 · 想常驻就跑成服务

`start.sh` / `start.bat` 只是薄壳，常驻交给系统：macOS 用 `launchd`、Linux 用 `systemd`、
Windows 用 `nssm`，命令统一是 `node server/src/main.js`，并带上 `BIND_HOST` / `ADMIN_TOKEN` 环境变量。

## 六、联调与验证

```bash
# 1. 启动网关（发行包解压后 scripts/start.sh 亦可）
cd hongguo-web/server && npm start            # 127.0.0.1:8000

# 2. 离线单测（夹具来自真实响应，已脱敏）
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.HongguoDramaTest"

# 3. 在线联调（服务不在 8000 时用 HONGGUO_BASE_URL 覆盖）
HONGGUO_INTEGRATION_TEST=1 HONGGUO_BASE_URL=127.0.0.1:8123 \
  ./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.HongguoDramaIntegrationTest"
```

在线测试覆盖：热播列表 → 详情 → 首集/末集 `Range` 取流（校验 2xx 与 `video/*`）、
关键词搜索、漫剧体裁列表。

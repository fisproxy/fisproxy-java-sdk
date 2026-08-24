# fisproxy

[FisProxy](https://fisproxy.org) 用户会话接口的官方 Java SDK。

在已登录的用户中心 **API** 页创建令牌，明文仅显示一次。本客户端不提供账号登录。

默认接入点：`https://api.fisproxy.org`。请求签名由 SDK 自动完成。

英文说明见 [README.md](README.md)。

## 安装

需要 Java 17 或更高版本，无第三方运行时依赖。

```bash
git clone https://github.com/nyaproxy/fisproxy-java-sdk.git
cd fisproxy-java-sdk
mvn -DskipTests install
```

```xml
<dependency>
  <groupId>org.fisproxy</groupId>
  <artifactId>fisproxy</artifactId>
  <version>0.1.0</version>
</dependency>
```

也可经 [JitPack](https://jitpack.io) 引用本仓库：

```xml
<dependency>
  <groupId>com.github.nyaproxy</groupId>
  <artifactId>fisproxy-java-sdk</artifactId>
  <version>v0.1.0</version>
</dependency>
```

```java
import org.fisproxy.Client;

Client client = Client.fromEnv(); // 读取环境变量 FISPROXY_API_TOKEN
```

也可直接传入令牌：

```java
Client client = new Client("fp_...");
```

| 环境变量 | 说明 |
|---|---|
| `FISPROXY_API_TOKEN` | 必填。用户中心 API 页签发的 Bearer 令牌 |
| `FISPROXY_API_BASE` | 可选。默认 `https://api.fisproxy.org` |
| `FISPROXY_CLIENT_ID` | 可选。进程内稳定的客户端标识，须匹配 `[A-Za-z0-9._~:-]{1,96}` |

`service_point`、`nfa_coin` 以及停止计费时的 `deduction` 均为十进制字符串，请勿转换为浮点数。

完整示例见 [`examples/SessionExample.java`](examples/SessionExample.java)。

---

## `me()` — 查询当前账户

对应 `GET /api/v1/me`。

```java
UserProfile profile = client.me();
```

### 返回 `UserProfile`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id()` | `String` | 用户 ID |
| `username()` | `String` | 用户名；缺失时为 `null` |
| `balances().servicePoint()` | `String` | 服务点数余额，十进制字符串 |
| `balances().nfaCoin()` | `String` | NFA Coin 余额，十进制字符串 |
| `balances().subscriptionPass()` | `String` | 订阅通行余额，十进制字符串 |
| `session()` | `Map<String, Object>` | 当前会话摘要；无会话时为 `null` |
| `raw()` | `Map<String, Object>` | 完整 JSON 响应 |

`raw()` 还包含：

```json
{
  "ok": true,
  "user": {
    "id": "…",
    "legacyAid": 12345,
    "username": "example",
    "email": "…",
    "status": "normal",
    "autoNfa": true,
    "createdAt": "2026-01-01T00:00:00.000Z",
    "balances": {
      "service_point": "12.50",
      "nfa_coin": "3",
      "subscription_pass": "0"
    },
    "currentSession": null
  },
  "bindings": [],
  "nfa": {
    "stock": { "total": 0, "available": 0, "hypixelAvailable": 0 },
    "trialAvailable": false
  }
}
```

会话存在时，`user.currentSession` / `profile.session()` 的主要字段：

| 字段 | 说明 |
|---|---|
| `id` | 内部会话 ID |
| `sessionId` | 对外会话号（连接地址前缀） |
| `serviceId` | 服务 ID |
| `state` | 会话状态，例如 `starting`、`running` |
| `target` | 上游目标 |
| `startedAt` | 创建时间（ISO 8601） |
| `billingStartedAt` | 开始计费时间；尚未接通时可能为空 |

接口不返回落地 IP。

---

## `services()` — 列出可用服务

对应 `GET /api/v1/me/services`。

```java
for (var service : client.services()) {
    System.out.println(service.get("id") + " " + service.get("name") + " " + service.get("hasAccess"));
}
```

### 返回

`List<Map<String, Object>>`，每项对应一个对当前用户可见的服务。完整响还可经 `client.request("GET", "/api/v1/me/services")` 取得。

`start(StartOptions.builder().serviceId(...).build())` 使用这里的 `id`。`hasAccess` 为 `false` 时表示当前权益不足。

---

## `status()` — 查询会话与连接入口

对应 `GET /api/v1/sessions/status`。

```java
SessionStatus status = client.status();
if (status.running()) {
    System.out.println(status.address());
}
```

### 返回 `SessionStatus`

| 字段 | 类型 | 说明 |
|---|---|---|
| `running()` | `boolean` | 当前是否存在会话 |
| `session()` | `Map<String, Object>` | 会话摘要；未运行时为 `null` |
| `address()` | `String` | 主连接地址 |
| `entrance()` | `String` | 与 `address()` 相同 |
| `entrances()` | `List<Entrance>` | 全部可用入口 |
| `raw()` | `Map<String, Object>` | 完整 JSON 响应 |

Minecraft 请连接 `status.address()`（或 `entrances().get(i).address()`），与加速页复制的字符串相同。更换出口 IP 后入口通常不变。

---

## `entrances(serviceId)` — 按服务查询入口

对应 `GET /api/v1/me/entrances?serviceId=`。

```java
client.entrances("us.hypixel");
```

未指定 `serviceId` 时，服务端默认查询 `default` 服务。服务不可见时返回 404。

### 返回

`List<Map<String, Object>>`，字段与 `status().entrances()` 相同：`id`、`name`、`host`、`address`。若当前已有会话，`address` 会带上会话号前缀。

---

## `start(...)` — 开始计费

对应 `POST /api/v1/sessions/start`。HTTP **202**，不会立即接通。

```java
Operation operation = client.start(StartOptions.builder().wait(true).build());
System.out.println(operation.status() + " " + operation.sessionId());
System.out.println(client.status().address());
```

`client.start()` 与默认 `wait=true` 等价。

### 参数（均为可选）

| 参数 | 说明 |
|---|---|
| `serviceId` | 服务 ID，来自 `services()` |
| `target` | 上游目标，例如 `mc.hypixel.net` |
| `autoNfa` | 是否自动分配 NFA |
| `nfaItemId` | 指定 NFA 账号 |
| `reuseNfaItemId` | 复用历史连接的选择 ID |
| `nfaSource` | `local` 或 `solar` |
| `nfaSku` | NFA SKU |
| `tryPreviousNfa` | 尝试复用上一份 NFA |
| `idempotencyKey` | 幂等键；缺省由 SDK 生成 |
| `wait` | 默认 `true`，轮询至操作结束 |
| `timeoutSeconds` | 等待超时秒数，默认 `180` |
| `intervalSeconds` | 轮询间隔秒数，默认 `1` |

`wait=false` 时立即返回排队中的 `Operation`。`wait=true` 且操作失败或取消时抛出 `OperationFailedException`。

已有冲突操作时抛出 `ConflictException`，`existingOperation()` 为正在执行的操作。

---

## `changeIp(...)` — 更换出口 IP

对应 `POST /api/v1/sessions/change-ip`。HTTP **202**。

```java
Operation operation = client.changeIp();
System.out.println(operation.routeAcked());
```

更换的是出口，连接入口通常不变。成功表示落地与路由已提交；路由真正生效前应确认 `result.routeAcked` 为 `true`。`wait=true`（默认）时，SDK 会等到操作结束，并在成功后继续等待 `routeAcked`。

---

## `stop()` — 停止计费

对应 `POST /api/v1/sessions/stop`。HTTP **200**，同步返回结算结果。

```java
StopResult stopped = client.stop();
System.out.println(stopped.duration() + " " + stopped.deduction());
```

### 返回 `StopResult`

| 字段 | 类型 | 说明 |
|---|---|---|
| `duration()` | `int` | 计费时长（分钟） |
| `deduction()` | `String` | 扣费金额，十进制字符串 |
| `session()` | `Map<String, Object>` | 停止前的会话快照 |
| `canceledOperations()` | `int` | 一并取消的未完成操作数 |
| `raw()` | `Map<String, Object>` | 完整 JSON 响应 |

---

## 异步操作

`start` 与 `changeIp` 返回 `Operation`。可用以下方法继续跟踪：

```java
Operation op = client.getOperation("op-…");
op = client.waitOperation("op-…", WaitOptions.builder().timeoutSeconds(180).intervalSeconds(1).build());
client.cancelOperation("op-…");
client.listOperations(ListOperationsOptions.builder()
        .status("running")
        .kind("session.start")
        .limit(20)
        .build());
```

`waitOperation` 在 `failed` 或 `canceled` 时抛出 `OperationFailedException`。

---

## 异常

| 异常 | 说明 |
|---|---|
| `AuthException` | 令牌无效、已吊销或账户被封禁 |
| `AdmissionException` | 请求未被接受；短暂失败会由 SDK 自动重试 |
| `ConflictException` | 已有冲突操作（HTTP 409），见 `existingOperation()` |
| `OperationFailedException` | 异步操作以 `failed` 或 `canceled` 结束，见 `operation()` |
| `OperationTimeoutException` | 等待操作超时 |
| `TransportException` | 网络错误 |

---

## 许可证

MIT

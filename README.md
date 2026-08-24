# fisproxy

Official Java SDK for the [FisProxy](https://fisproxy.org) user session API.

Create an API token on the signed-in **API** page. The plaintext is shown once. This client does not provide account sign-in.

Default endpoint: `https://api.fisproxy.org`. Request signing is handled by the SDK.

中文说明（含每个方法的调用方式与返回字段）见 [README.zh-CN.md](README.zh-CN.md).

## Install

Java 17+. No third-party runtime dependencies.

Git:

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

JitPack (installs from this repository):

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>
<dependency>
  <groupId>com.github.nyaproxy</groupId>
  <artifactId>fisproxy-java-sdk</artifactId>
  <version>v0.1.0</version>
</dependency>
```

Gradle:

```gradle
repositories { maven { url 'https://jitpack.io' } }
implementation 'com.github.nyaproxy:fisproxy-java-sdk:v0.1.0'
```

## Quick start

```java
import org.fisproxy.Client;
import org.fisproxy.Operation;
import org.fisproxy.SessionStatus;
import org.fisproxy.UserProfile;

Client client = Client.fromEnv(); // FISPROXY_API_TOKEN
UserProfile me = client.me();
System.out.println(me.balances().servicePoint()); // decimal string, not float

Operation operation = client.start();
SessionStatus status = client.status();
System.out.println(status.address()); // {sessionId}.{host}

client.stop();
```

Environment:

| Variable | Meaning |
|---|---|
| `FISPROXY_API_TOKEN` | Bearer token from the API page |
| `FISPROXY_API_BASE` | Override API host. Default `https://api.fisproxy.org` |
| `FISPROXY_CLIENT_ID` | Optional stable process id (`[A-Za-z0-9._~:-]{1,96}`) |

`service_point` and `nfa_coin` are decimal strings. Do not convert them to floats.

## Methods

| Method | Notes |
|---|---|
| `me()` | Profile, bindings, decimal `balances` |
| `services()` | Visible services for `start(serviceId=...)` |
| `status()` | `running`, `address`, `entrances` |
| `entrances(serviceId)` | Host before start; `{sessionId}.{host}` after |
| `start(StartOptions)` | Queues a start. `wait=true` (default) polls until success |
| `changeIp(ChangeIpOptions)` | Queues an IP change. Default wait includes route ACK |
| `stop()` | Stops billing and returns `{duration, deduction}` |
| `getOperation` / `waitOperation` / `cancelOperation` | Follow async start / change-ip |

`start` / `changeIp` send an `Idempotency-Key` automatically. Conflicts throw `ConflictException` with `existingOperation()`.

Start fields (all optional): `serviceId`, `target`, `autoNfa`, `nfaItemId`, `reuseNfaItemId`, `nfaSource`, `nfaSku`, `tryPreviousNfa`.

The Minecraft connect string is `status.address()` / `status.entrances().get(i).address()`. Change-ip rotates the exit; the entrance usually stays.

## Example

See [`examples/SessionExample.java`](examples/SessionExample.java).

```bash
export FISPROXY_API_TOKEN=...
mvn -q -DskipTests package
javac -cp target/fisproxy-0.1.0.jar examples/SessionExample.java
java -cp target/fisproxy-0.1.0.jar:examples SessionExample
```

## Errors

- `AuthException` — token revoked, banned, or unauthorized
- `AdmissionException` — the API rejected this request; transient cases are retried
- `ConflictException` — another operation is already running (409)
- `OperationFailedException` — async start / change-ip ended `failed` or `canceled`
- `OperationTimeoutException` — polling deadline
- `TransportException` — network failure

## License

MIT

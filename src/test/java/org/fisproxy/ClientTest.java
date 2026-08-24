package org.fisproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fisproxy.internal.Hmac;
import org.fisproxy.internal.Json;
import org.junit.jupiter.api.Test;

class ClientTest {
    @Test
    void meReadsCurrentSession() {
        FakeTransport transport = new FakeTransport();
        Map<String, Object> user = Json.asObject(transport.mePayload.get("user"));
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", "s1");
        session.put("sessionId", "4242");
        session.put("state", "running");
        user.put("currentSession", session);
        transport.mePayload.put("user", user);
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        UserProfile me = client.me();
        assertNotNull(me.session());
        assertEquals("4242", me.session().get("sessionId"));
    }

    @Test
    void meKeepsDecimalStrings() {
        FakeTransport transport = new FakeTransport();
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        UserProfile me = client.me();
        assertEquals("user-1", me.id());
        assertEquals("12.50", me.balances().servicePoint());
        assertInstanceOf(String.class, me.balances().servicePoint());
        assertInstanceOf(String.class, me.balances().nfaCoin());
    }

    @Test
    void idleStatusAndHmacHeaders() {
        FakeTransport transport = new FakeTransport();
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        SessionStatus status = client.status();
        assertFalse(status.running());
        assertEquals(List.of(), status.entrances());
        Call signed = transport.find("/api/v1/sessions/status");
        assertFalse(signed.headers.containsKey("requestKey"));
        assertTrue(signed.headers.get("Authorization").startsWith("Bearer "));
        assertEquals("0", signed.headers.get("X-FP-Sequence"));
    }

    @Test
    void startWaitsAndSendsIdempotencyKey() {
        FakeTransport transport = new FakeTransport();
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        Operation operation = client.start(StartOptions.builder()
                .target("mc.hypixel.net")
                .autoNfa(true)
                .wait(true)
                .intervalSeconds(0.01)
                .build());
        assertTrue(operation.succeeded());
        assertEquals("4242", operation.sessionId());
        assertEquals("4242.hk.example", operation.entrances().get(0).address());
        Call start = transport.find("/api/v1/sessions/start");
        assertEquals("{\"target\":\"mc.hypixel.net\",\"autoNfa\":true}", new String(start.body, StandardCharsets.UTF_8));
        assertTrue(start.headers.containsKey("Idempotency-Key"));
        assertEquals("POST", start.method);
    }

    @Test
    void stopDeductionIsString() {
        FakeTransport transport = new FakeTransport();
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        StopResult stopped = client.stop();
        assertEquals("0.40", stopped.deduction());
        assertEquals(12, stopped.duration());
    }

    @Test
    void admissionRefreshOnExpired() {
        FakeTransport transport = new FakeTransport();
        transport.failNextStatus = new Fail(401, Map.of(
                "ok", false,
                "errorCode", "ADMISSION_EXPIRED",
                "message", "expired"));
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        SessionStatus status = client.status();
        assertFalse(status.running());
        List<Call> admissions = transport.findAll("/api/v1/auth/admission");
        assertEquals(2, admissions.size());
        assertFalse(admissions.get(0).headers.containsKey("X-FP-Signature"));
        assertEquals(
                Hmac.sha256B64Url(admissions.get(0).body),
                admissions.get(0).headers.get("X-FP-Content-SHA256"));
        assertEquals(
                Integer.toString(admissions.get(0).body.length),
                admissions.get(0).headers.get("X-FP-Content-Length"));
    }

    @Test
    void conflictExposesExistingOperation() {
        FakeTransport transport = new FakeTransport();
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        ConflictException raised = assertThrows(ConflictException.class, () ->
                client.changeIp(ChangeIpOptions.builder().wait(false).build()));
        assertEquals("OPERATION_CONFLICT", raised.errorCode());
        assertEquals("op-existing", raised.existingOperation().get("id"));
    }

    @Test
    void queryIsIncludedInSignatureTarget() {
        FakeTransport transport = new FakeTransport();
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        client.entrances("svc-1");
        Call call = transport.find("/api/v1/me/entrances");
        assertTrue(call.url.endsWith("/api/v1/me/entrances?serviceId=svc-1"));
    }

    @Test
    void sequenceIncrements() {
        FakeTransport transport = new FakeTransport();
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        client.status();
        client.me();
        List<Call> signed = new ArrayList<>();
        for (Call call : transport.calls) {
            if (call.headers.containsKey("X-FP-Sequence")) {
                signed.add(call);
            }
        }
        assertEquals("0", signed.get(0).headers.get("X-FP-Sequence"));
        assertEquals("1", signed.get(1).headers.get("X-FP-Sequence"));
    }

    @Test
    void fromEnv() {
        FakeTransport transport = new FakeTransport();
        Map<String, String> env = new HashMap<>();
        env.put("FISPROXY_API_TOKEN", "env-token");
        env.put("FISPROXY_API_BASE", "https://api.fisproxy.org");
        env.put("FISPROXY_CLIENT_ID", "client-1");
        Client client = Client.fromEnv(env, ClientOptions.builder().transport(transport).build());
        assertEquals("client-1", client.clientId());
        client.me();
    }

    @Test
    void missingToken() {
        assertThrows(IllegalArgumentException.class, () -> new Client("  "));
    }

    @Test
    void failedOperation() {
        FakeTransport transport = new FakeTransport();
        transport.operationPayloads = new ArrayList<>();
        transport.operationPayloads.add(Map.of(
                "ok", true,
                "operation", Map.of(
                        "id", "op-start",
                        "kind", "session.start",
                        "status", "failed",
                        "progressPhase", "done",
                        "errorCode", "INSUFFICIENT_BALANCE",
                        "message", "not enough service_point")));
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        OperationFailedException raised = assertThrows(OperationFailedException.class, () ->
                client.start(StartOptions.builder().wait(true).intervalSeconds(0.01).build()));
        assertEquals("INSUFFICIENT_BALANCE", raised.errorCode());
    }

    @Test
    void unauthorizedToken() {
        FakeTransport transport = new FakeTransport();
        transport.failNextStatus = new Fail(401, Map.of(
                "ok", false,
                "errorCode", "UNAUTHORIZED",
                "message", "nope"));
        Client client = new Client("tok_live", ClientOptions.builder().clientId("client-1").transport(transport).build());
        assertThrows(AuthException.class, client::me);
    }

    @Test
    void decimalStrRejectsBool() {
        assertThrows(IllegalArgumentException.class, () -> DecimalStrings.decimalStr(true));
        assertEquals("1.10", DecimalStrings.decimalStr("1.10"));
        assertEquals("2", DecimalStrings.decimalStr(2));
    }

    private static String admissionToken(String clientId, long expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 2L);
        payload.put("kid", "k1");
        payload.put("issuer", "fisproxy");
        payload.put("edgeProvider", "cloudflare");
        payload.put("aud", "api.fisproxy.org");
        payload.put("admissionId", "adm1");
        payload.put("subject", "subj1");
        payload.put("subjectEpoch", 1L);
        payload.put("credentialId", "cred1");
        payload.put("credentialKind", "user_api_token");
        payload.put("clientId", clientId);
        payload.put("serverEpoch", "epoch1");
        payload.put("issuedAt", 1700000000L);
        payload.put("expiresAt", expiresAt);
        return "a2." + Hmac.b64url(Json.dumps(payload)) + ".dGFn";
    }

    private record Fail(int status, Map<String, Object> payload) {
    }

    private record Call(String method, String url, Map<String, String> headers, byte[] body) {
    }

    private static final class FakeTransport implements Transport {
        private final List<Call> calls = new ArrayList<>();
        private long admissionExpiresAt = 4_000_000_000L;
        private Map<String, Object> statusPayload = map(
                "ok", true,
                "running", false,
                "session", null,
                "entrances", List.of());
        private Map<String, Object> startPayload = map(
                "ok", true,
                "operation", map(
                        "id", "op-start",
                        "kind", "session.start",
                        "status", "queued",
                        "progressPhase", "queued",
                        "createdAt", "2026-01-01T00:00:00.000Z",
                        "updatedAt", "2026-01-01T00:00:00.000Z"));
        private List<Map<String, Object>> operationPayloads = new ArrayList<>();
        private Map<String, Object> stopPayload = map(
                "ok", true,
                "duration", 12L,
                "deduction", "0.40",
                "session", Map.of("legacySessionId", "4242"));
        private Map<String, Object> mePayload = new LinkedHashMap<>();
        private Fail failNextStatus;
        private final String requestKey;

        private FakeTransport() {
            byte[] key = new byte[32];
            for (int index = 0; index < key.length; index++) {
                key[index] = (byte) index;
            }
            this.requestKey = Hmac.b64url(key);
            mePayload.put("ok", true);
            mePayload.put("user", map(
                    "id", "user-1",
                    "username", "script",
                    "balances", map(
                            "service_point", "12.50",
                            "nfa_coin", "3",
                            "subscription_pass", "0")));
            mePayload.put("bindings", List.of());
            mePayload.put("nfa", map(
                    "stock", map("total", 0L, "available", 0L, "hypixelAvailable", 0L),
                    "trialAvailable", false));
            Map<String, Object> entrance = map(
                    "id", "e1",
                    "name", "Hong Kong",
                    "host", "hk.example",
                    "address", "4242.hk.example");
            operationPayloads.add(map(
                    "ok", true,
                    "operation", map(
                            "id", "op-start",
                            "kind", "session.start",
                            "status", "succeeded",
                            "progressPhase", "done",
                            "result", map(
                                    "kind", "start",
                                    "sessionId", "4242",
                                    "entrances", List.of(entrance)),
                            "createdAt", "2026-01-01T00:00:00.000Z",
                            "updatedAt", "2026-01-01T00:00:00.000Z")));
        }

        @Override
        public RawResponse exchange(String method, String url, Map<String, String> headers, byte[] body) {
            byte[] payload = body == null ? new byte[0] : body;
            calls.add(new Call(method, url, new LinkedHashMap<>(headers), payload));
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            String target = path + (query == null || query.isEmpty() ? "" : "?" + query);
            if ("POST".equals(method) && "/api/v1/auth/admission".equals(path)) {
                if (!Integer.toString(payload.length).equals(headers.get("X-FP-Content-Length"))) {
                    throw new AssertionError("admission missing X-FP-Content-Length");
                }
                if (!Hmac.sha256B64Url(payload).equals(headers.get("X-FP-Content-SHA256"))) {
                    throw new AssertionError("admission missing X-FP-Content-SHA256");
                }
                Map<String, Object> sent = Json.parseObject(new String(payload, StandardCharsets.UTF_8));
                String token = admissionToken(String.valueOf(sent.get("clientId")), admissionExpiresAt);
                Map<String, Object> response = map(
                        "ok", true,
                        "admission", token,
                        "requestKey", requestKey,
                        "subject", "subj1",
                        "admissionId", "adm1",
                        "expiresAt", admissionExpiresAt,
                        "serverTime", Instant.now().toEpochMilli(),
                        "serverEpoch", "epoch1");
                return json(200, response);
            }
            if (failNextStatus != null) {
                Fail fail = failNextStatus;
                failNextStatus = null;
                return json(fail.status, fail.payload);
            }
            if ("GET".equals(method) && "/api/v1/me".equals(path)) {
                assertSigned(method, target, payload, headers);
                return json(200, mePayload);
            }
            if ("GET".equals(method) && "/api/v1/sessions/status".equals(path)) {
                assertSigned(method, target, payload, headers);
                return json(200, statusPayload);
            }
            if ("POST".equals(method) && "/api/v1/sessions/start".equals(path)) {
                assertSigned(method, target, payload, headers);
                return json(202, startPayload);
            }
            if ("GET".equals(method) && path.startsWith("/api/v1/operations/")) {
                assertSigned(method, target, payload, headers);
                Map<String, Object> operation = operationPayloads.size() > 1
                        ? operationPayloads.remove(0)
                        : operationPayloads.get(0);
                return json(200, operation);
            }
            if ("POST".equals(method) && "/api/v1/sessions/stop".equals(path)) {
                assertSigned(method, target, payload, headers);
                return json(200, stopPayload);
            }
            if ("POST".equals(method) && "/api/v1/sessions/change-ip".equals(path)) {
                assertSigned(method, target, payload, headers);
                return json(409, map(
                        "ok", false,
                        "errorCode", "OPERATION_CONFLICT",
                        "message", "already running",
                        "existingOperation", map("id", "op-existing", "status", "running")));
            }
            if ("GET".equals(method) && "/api/v1/me/entrances".equals(path)) {
                assertSigned(method, target, payload, headers);
                return json(200, map(
                        "ok", true,
                        "entrances", List.of(map("id", "e1", "host", "hk.example"))));
            }
            throw new AssertionError("unexpected " + method + " " + url);
        }

        private void assertSigned(String method, String target, byte[] body, Map<String, String> headers) {
            String blob = Json.stringify(headers);
            if (blob.contains(requestKey) || headers.containsKey("requestKey")) {
                throw new AssertionError("requestKey leaked into headers");
            }
            for (String name : List.of(
                    "X-FP-Admission",
                    "X-FP-Subject",
                    "X-FP-Timestamp",
                    "X-FP-Client",
                    "X-FP-Sequence",
                    "X-FP-Content-Length",
                    "X-FP-Content-SHA256",
                    "X-FP-Signature")) {
                if (!headers.containsKey(name)) {
                    throw new AssertionError("missing " + name);
                }
            }
            String canonical = Hmac.canonicalRequest(
                    "api.fisproxy.org",
                    "subj1",
                    "cred1",
                    "adm1",
                    headers.get("X-FP-Client"),
                    headers.get("X-FP-Sequence"),
                    headers.get("X-FP-Timestamp"),
                    method,
                    target,
                    headers.getOrDefault("Content-Type", ""),
                    headers.get("X-FP-Content-Length"),
                    headers.getOrDefault("Idempotency-Key", ""),
                    Hmac.sha256B64Url(body));
            String expected = Hmac.signRequest(requestKey, canonical);
            if (!expected.equals(headers.get("X-FP-Signature"))) {
                throw new AssertionError("HMAC mismatch");
            }
            if (!Hmac.sha256B64Url(body).equals(headers.get("X-FP-Content-SHA256"))) {
                throw new AssertionError("body digest mismatch");
            }
            if (!Integer.toString(body.length).equals(headers.get("X-FP-Content-Length"))) {
                throw new AssertionError("content length mismatch");
            }
        }

        private Call find(String pathSuffix) {
            for (Call call : calls) {
                if (call.url.contains(pathSuffix)) {
                    return call;
                }
            }
            throw new AssertionError("missing call " + pathSuffix);
        }

        private List<Call> findAll(String pathSuffix) {
            List<Call> found = new ArrayList<>();
            for (Call call : calls) {
                if (call.url.contains(pathSuffix)) {
                    found.add(call);
                }
            }
            return found;
        }

        private static RawResponse json(int status, Map<String, Object> payload) {
            return new RawResponse(status, Map.of("Content-Type", "application/json"), Json.dumps(payload));
        }

        private static Map<String, Object> map(Object... pairs) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (int index = 0; index < pairs.length; index += 2) {
                result.put((String) pairs[index], pairs[index + 1]);
            }
            return result;
        }
    }
}

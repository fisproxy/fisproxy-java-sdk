package org.fisproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.fisproxy.internal.Hmac;
import org.fisproxy.internal.Json;
import org.junit.jupiter.api.Test;

class HmacTest {
    @Test
    void emptyBodyDigest() throws Exception {
        assertEquals(Hmac.sha256B64Url(new byte[0]), Hmac.EMPTY_BODY_SHA256);
        assertEquals("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU", Hmac.EMPTY_BODY_SHA256);
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(new byte[0]);
        assertEquals(Hmac.b64url(expected), Hmac.EMPTY_BODY_SHA256);
    }

    @Test
    void canonicalAndSignatureVector() throws Exception {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        String bodySha = Hmac.sha256B64Url(new byte[0]);
        String canonical = Hmac.canonicalRequest(
                "api.fisproxy.org",
                "subject",
                "cred",
                "adm",
                "client-1",
                "0",
                "1730000000000",
                "GET",
                "/api/v1/sessions/status",
                "",
                "0",
                "",
                bodySha);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        assertEquals(Hmac.b64url(expected), Hmac.signRequest(Hmac.b64url(key), canonical));
        assertEquals("IQDzGpbe6m6LW0wpp8ugYmcgEuYyziJxvGP6B1RJY-s", Hmac.signRequest(Hmac.b64url(key), canonical));
    }

    @Test
    void jsonIsCompactUtf8() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("autoNfa", true);
        body.put("target", "mc.hypixel.net");
        assertEquals("{\"autoNfa\":true,\"target\":\"mc.hypixel.net\"}", Json.stringify(body));
        byte[] dumped = Json.dumps(body);
        assertEquals("{\"autoNfa\":true,\"target\":\"mc.hypixel.net\"}", new String(dumped, StandardCharsets.UTF_8));
    }

    @Test
    void contentTypeNormalization() {
        assertEquals("application/json;charset=utf-8", Hmac.normalizeContentType("Application/JSON ; Charset = UTF-8"));
    }

    @Test
    void clientIdCharset() {
        assertTrue(Hmac.validClientId("script-client_1.abc:def~"));
        assertFalse(Hmac.validClientId(""));
        assertFalse(Hmac.validClientId("bad id"));
        assertFalse(Hmac.validClientId("x".repeat(97)));
    }

    @Test
    void parseAdmissionPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 2L);
        payload.put("aud", "api.fisproxy.org");
        payload.put("admissionId", "adm");
        payload.put("subject", "sub");
        payload.put("credentialId", "cred");
        payload.put("clientId", "client-1");
        payload.put("serverEpoch", "epoch");
        payload.put("expiresAt", 1730000300L);
        String token = "a2." + Hmac.b64url(Json.dumps(payload)) + ".dGFn";
        Map<String, Object> parsed = Hmac.parseAdmissionPayload(token);
        assertEquals("api.fisproxy.org", parsed.get("aud"));
        assertThrows(IllegalArgumentException.class, () -> Hmac.parseAdmissionPayload("not-a-token"));
    }
}

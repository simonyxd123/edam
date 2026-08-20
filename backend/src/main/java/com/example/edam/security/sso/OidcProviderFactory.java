package com.example.edam.security.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * OIDC Provider 工厂（v3.3 W-4.3）
 *
 * 生产实现应使用 Spring Security OAuth2 Client：
 *   - org.springframework.boot:spring-boot-starter-oauth2-client
 *
 * 本类为简化骨架，实现 OAuth 2.0 Authorization Code Flow + PKCE
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OidcProviderFactory {

    private static final SecureRandom RNG = new SecureRandom();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 创建 OIDC Provider
     */
    public SsoProvider create(SsoProperties.ProviderConfig config) {
        log.info("oidc_provider_create provider_id={} issuer={}",
            config.getId(), config.getIssuer());
        return new OidcProvider(config);
    }

    /**
     * OIDC Provider 实现
     */
    static class OidcProvider implements SsoProvider {
        private final SsoProperties.ProviderConfig config;

        OidcProvider(SsoProperties.ProviderConfig config) {
            this.config = config;
        }

        @Override
        public String getId() { return config.getId(); }

        @Override
        public String getDisplayName() { return config.getDisplayName(); }

        @Override
        public String getProtocol() { return "oidc"; }

        @Override
        public String getAuthorizationRedirectUrl() {
            // OAuth 2.0 Authorization Code Flow + PKCE
            String state = generateRandomString(32);
            String codeVerifier = generateRandomString(64);
            String codeChallenge = base64UrlEncode(sha256(codeVerifier).getBytes());

            StringBuilder url = new StringBuilder(config.getAuthorizationUri());
            url.append("?response_type=code");
            url.append("&client_id=").append(urlEncode(config.getClientId()));
            url.append("&redirect_uri=").append(urlEncode(config.getRedirectUri()));
            url.append("&scope=").append(urlEncode("openid profile email"));
            url.append("&state=").append(state);
            url.append("&code_challenge=").append(codeChallenge);
            url.append("&code_challenge_method=S256");
            return url.toString();
        }

        @Override
        public SsoUserInfo resolveUserInfo(SsoCallbackPayload payload) {
            try {
                // 1. 用 code 换 access_token
                String accessToken = exchangeCodeForToken(payload.getRawPayload());

                // 2. 用 access_token 拉 userinfo
                String userInfoJson = fetchUserInfo(accessToken);

                // 3. 解析 userinfo
                JsonNode userInfo = MAPPER.readTree(userInfoJson);
                SsoUserInfo info = new SsoUserInfo();
                info.setProviderId(config.getId());

                SsoProperties.AttributeMapping mapping = config.getAttributeMapping();
                info.setUserId(textOrNull(userInfo, mapping.getUserId()));
                info.setEmployeeNo(textOrNull(userInfo, mapping.getEmployeeNo()));
                info.setRealName(textOrNull(userInfo, mapping.getRealName()));
                info.setEmail(textOrNull(userInfo, mapping.getEmail()));
                info.setDepartment(textOrNull(userInfo, mapping.getDepartment()));

                log.info("oidc_userinfo_resolved provider_id={} user_id={}",
                    config.getId(), info.getUserId());
                return info;
            } catch (Exception e) {
                throw new RuntimeException("OIDC resolve userinfo failed", e);
            }
        }

        @Override
        public Map<String, Object> getConfig() {
            return Map.of(
                "protocol", "oidc",
                "issuer", config.getIssuer(),
                "client_id", config.getClientId(),
                "authorization_uri", config.getAuthorizationUri()
            );
        }

        /**
         * Code 换 Token
         */
        private String exchangeCodeForToken(String code) throws Exception {
            String body = "grant_type=authorization_code" +
                "&client_id=" + urlEncode(config.getClientId()) +
                "&client_secret=" + urlEncode(config.getClientSecret()) +
                "&code=" + urlEncode(code) +
                "&redirect_uri=" + urlEncode(config.getRedirectUri());

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getTokenUri()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Token exchange failed: " + response.statusCode());
            }

            JsonNode json = MAPPER.readTree(response.body());
            return json.get("access_token").asText();
        }

        /**
         * 拉 userinfo
         */
        private String fetchUserInfo(String accessToken) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getUserinfoUri()))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString());
            return response.body();
        }

        private static String textOrNull(JsonNode node, String field) {
            return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
        }

        private static String generateRandomString(int length) {
            byte[] bytes = new byte[length];
            RNG.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        private static byte[] sha256(String input) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return md.digest(input.getBytes(StandardCharsets.US_ASCII));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static String base64UrlEncode(byte[] bytes) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }

        private static String urlEncode(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
    }
}
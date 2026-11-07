package com.example.leave.mcp;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MCP クライアントのディスカバリの起点を固定する。
 * 401 → WWW-Authenticate の resource_metadata と scope → PRM → authorization_servers（Keycloak）。
 * Keycloak 抜きで動く（JwtDecoder はモック）。
 */
@SpringBootTest
class McpAuthorizationTest {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    WebApplicationContext webApplicationContext;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(this.webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    void トークン無しの401はPRMの場所とscopeを広告する() throws Exception {
        mockMvc.perform(post("/mcp"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer resource_metadata=\"http://localhost:19003/.well-known/oauth-protected-resource\", "
                    + "scope=\"leave-mcp-server leave-mcp:read\""));
    }

    @Test
    void 検証済みトークンならMCPのinitializeに応答する() throws Exception {
        mockMvc.perform(post("/mcp").with(jwt().jwt(jwt -> jwt.claim("preferred_username", "yamada")))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                     "capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                    """))
            .andExpect(status().isOk())
            .andExpect(header().exists("Mcp-Session-Id"));
    }

    @Test
    void PRMはresourceとauthorization_serversとscopesを含む() throws Exception {
        // 401 / 403 が指すルートと、RFC 9728 §3.1 の形（resource のパスを付けた URL）のどちらでも同じ JSON
        for (String path : new String[] {"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"}) {
            mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost:19003/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("http://localhost:19000/realms/sample-corp"))
                .andExpect(jsonPath("$.scopes_supported", Matchers.contains("leave-mcp-server", "leave-mcp:read")))
                .andExpect(jsonPath("$.bearer_methods_supported", Matchers.contains("header")));
        }
    }
}

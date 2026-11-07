package com.example.leave.api.config;

import com.example.leave.api.service.UserService;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 検証済みの JWT → {@code Authentication}。
 *
 * <p>トークンから使うのは「誰か」（{@code preferred_username} = 社員マスタの id）だけ。
 * 「何ができるか」（{@code ROLE_employee} / {@code ROLE_manager}）は、このアプリの社員マスタから決める
 * （{@link UserService#roles}）。IdP にはアプリごとの権限を持たせない。
 * トークンに {@code realm_access.roles} などが載っていても見ない。
 */
@Component
class EmployeeAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserService userService;

    EmployeeAuthenticationConverter(UserService userService) {
        this.userService = userService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        List<GrantedAuthority> authorities = userService.roles(username).stream()
            .sorted()
            .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
        // 主体名は preferred_username。無ければ sub（その場合は社員マスタに当たらず、権限なしになる）。
        return new JwtAuthenticationToken(jwt, authorities, username != null ? username : jwt.getSubject());
    }
}

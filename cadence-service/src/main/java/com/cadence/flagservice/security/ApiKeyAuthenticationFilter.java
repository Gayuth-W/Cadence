package com.cadence.flagservice.security;

import com.cadence.flagservice.security.domain.ApiKey;
import com.cadence.flagservice.security.domain.ApiKeyScope;
import com.cadence.flagservice.security.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates the SDK's machine-to-machine traffic on {@code /sdk/v1/**}.
 *
 * <p>This is the split that real flag platforms draw and that most homegrown ones miss: the console
 * is a human plane (JWT, roles, audit attribution) and the SDK is a service plane (scoped API key,
 * no identity, no mutations). Handing an application a user's JWT so it can evaluate a flag would
 * mean every pod in the fleet holds a credential that can force a production rollback.
 *
 * <p>The principal is set to {@code service:<key name>} so that any incidental audit entry originating
 * from the data plane is visibly not a person.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    public static final String HEADER = "X-Cadence-Api-Key";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String presented = request.getHeader(HEADER);
        if (presented != null && !presented.isBlank()) {
            Optional<ApiKey> key = apiKeyService.verify(presented);
            if (key.isPresent()) {
                ApiKey apiKey = key.get();
                List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                        apiKey.getScopes().stream()
                                .map(ApiKeyScope::authority)
                                .map(SimpleGrantedAuthority::new)
                                .toList());
                authorities.add(new SimpleGrantedAuthority("ROLE_SERVICE"));

                var authentication = new UsernamePasswordAuthenticationToken(
                        "service:" + apiKey.getName(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // The environment the key is scoped to decides which flags this request may see.
                // Taking it from the key rather than a request header means a compromised staging key
                // cannot read production flag configuration by flipping a header.
                request.setAttribute(RequestAttributes.ENVIRONMENT, apiKey.getEnvironment());
            } else {
                log.debug("Rejected API key on {}", request.getRequestURI());
            }
        }
        chain.doFilter(request, response);
    }

    /** Request-scoped keys set by this filter. */
    public static final class RequestAttributes {
        public static final String ENVIRONMENT = "cadence.environment";

        private RequestAttributes() {
        }
    }
}

package com.rcf.imas.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.cors.CorsUtils;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req,
            HttpServletResponse res,
            FilterChain chain
    ) throws ServletException, IOException {

        // Allow CORS preflight requests
        if (CorsUtils.isPreFlightRequest(req)) {
            chain.doFilter(req, res);
            return;
        }

        String header = req.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {

            String token = header.substring(7);

            try {
                JwtService.FinalToken t =
                        jwtService.parseFinalToken(token);

                var auth = new UsernamePasswordAuthenticationToken(
                        t,
                        null,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_" + t.roleName()
                                )
                        )
                );

                SecurityContextHolder
                        .getContext()
                        .setAuthentication(auth);

            } catch (JwtService.ExpiredTokenException e) {
                reject(res, "Token expired", "TOKEN_EXPIRED");
                return;

            } catch (JwtService.InvalidTokenException e) {
                reject(res, "Invalid token", "TOKEN_INVALID");
                return;
            }
        }

        chain.doFilter(req, res);
    }

    private void reject(
            HttpServletResponse res,
            String error,
            String code
    ) throws IOException {

        res.setStatus(401);
        res.setContentType("application/json");

        res.getWriter().write(
                "{\"error\":\"" + error +
                "\",\"code\":\"" + code + "\"}"
        );
    }
}
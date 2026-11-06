package com.esep.common.web;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
/** Non-sensitive configured replica label for local routing verification. Never a user identity. */
@Component
public class LedgerInstanceFilter extends OncePerRequestFilter {
    private final String instance;
    public LedgerInstanceFilter(@Value("${LEDGER_INSTANCE:ledger}") String instance) {
        if(!instance.matches("[A-Za-z0-9_-]{1,32}"))throw new IllegalArgumentException("Invalid replica label");
        this.instance=instance;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        response.setHeader("X-Ledger-Instance",instance);chain.doFilter(request,response);
    }
}

package com.esep.observability;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.*;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class HttpRequestLog extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        String supplied=request.getHeader("X-Correlation-ID");
        String correlation=supplied!=null && supplied.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")?supplied:UUID.randomUUID().toString();
        response.setHeader("X-Correlation-ID",correlation);
        boolean completed=false;
        try{chain.doFilter(request,response);completed=true;}finally{
            if(request.getRequestURI().startsWith("/api/")){
                Object pattern=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String method=Set.of("GET","POST","PATCH","OPTIONS","HEAD").contains(request.getMethod())?request.getMethod():"OTHER";
                LoggerFactory.getLogger(HttpRequestLog.class).atInfo().addKeyValue("correlation_id",correlation)
                    .addKeyValue("route",pattern==null?"security":pattern.toString()).addKeyValue("method",method)
                    .addKeyValue("status",completed || response.isCommitted()?response.getStatus():500).log("HTTP request completed");
            }
        }
    }
}

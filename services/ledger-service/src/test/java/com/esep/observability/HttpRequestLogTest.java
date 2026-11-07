package com.esep.observability;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;
class HttpRequestLogTest {
    @Test void exceptionBeforeContainerErrorHandlingIsNotLoggedAsSuccess() {
        var request=new MockHttpServletRequest("GET","/api/accounts/12345");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/accounts/{id}");
        request.addHeader("X-Correlation-ID","private-sentinel");
        var response=new MockHttpServletResponse();
        var logger=(Logger)LoggerFactory.getLogger(HttpRequestLog.class);
        var sink=new ListAppender<ILoggingEvent>();sink.start();logger.addAppender(sink);
        try {
            assertThatThrownBy(()->new HttpRequestLog().doFilter(request,response,(r,s)->{
                throw new ServletException("private-sentinel");
            })).isInstanceOf(ServletException.class);
            var event=sink.list.getLast();
            var fields=event.getKeyValuePairs().stream().collect(Collectors.toMap(p->p.key,p->p.value));
            assertThat(fields.get("status")).isEqualTo(500);
            assertThat(fields.get("route")).isEqualTo("/api/accounts/{id}");
            assertThat(fields.toString()+event.getFormattedMessage()).doesNotContain("private-sentinel","/api/accounts/12345");
            assertThatCode(()->java.util.UUID.fromString(response.getHeader("X-Correlation-ID"))).doesNotThrowAnyException();
        }finally{logger.detachAppender(sink);sink.stop();}
    }
}

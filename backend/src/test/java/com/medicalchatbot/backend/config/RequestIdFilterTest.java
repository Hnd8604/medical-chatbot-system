package com.medicalchatbot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void everyRequestGetsAnIdInTheLogsAndTheResponse() throws Exception {
        AtomicReference<String> loggedId = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response,
                new MockFilterChain() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse servletResponse) {
                        loggedId.set(MDC.get(RequestIdFilter.MDC_KEY));
                    }
                });

        assertThat(loggedId.get()).isNotBlank();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(loggedId.get());
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("cleared after the request").isNull();
    }

    @Test
    void aWellFormedIdFromTheCallerIsKept() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "proxy-1234");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("proxy-1234");
    }

    @Test
    void anIdThatCouldForgeLogLinesIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "abc\nERROR forged line");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).doesNotContain("\n").hasSize(36);
    }

    @Test
    void callsToChatbotServiceCarryTheRequestId() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        MockClientHttpRequest outgoing = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://chatbot/chat"));

        RestClientConfig.forwardRequestId().intercept(outgoing, new byte[0],
                (request, body) -> new MockClientHttpResponse(new byte[0], 200));

        assertThat(outgoing.getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo("req-42");
    }

    @Test
    void backgroundTasksKeepTheRequestId() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-7");
        AtomicReference<String> seenByTask = new AtomicReference<>();
        Runnable decorated = new AsyncConfig().mdcTaskDecorator()
                .decorate(() -> seenByTask.set(MDC.get(RequestIdFilter.MDC_KEY)));
        MDC.clear();

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(seenByTask.get()).isEqualTo("req-7");
    }
}

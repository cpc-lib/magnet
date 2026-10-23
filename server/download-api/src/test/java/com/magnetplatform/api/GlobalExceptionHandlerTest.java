package com.magnetplatform.api;

import com.magnetplatform.domain.common.DomainException;
import com.magnetplatform.domain.common.ErrorCodes;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void disconnectedStreamDoesNotWriteJsonError() throws Exception {
        mvc.perform(get("/disconnected").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void tomcatClientAbortDoesNotWriteJsonError() throws Exception {
        mvc.perform(get("/aborted").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void localizedIoOnEventStreamDoesNotWriteJson() throws Exception {
        mvc.perform(get("/localized-disconnect").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void localizedIoAfterStreamCommitPreservesWrittenEvents() throws Exception {
        mvc.perform(get("/localized-disconnect").param("committed", "true")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string("data: ready\n\n"));
    }

    @Test
    void uncommittedStreamFailureReturnsJsonInternalError() throws Exception {
        mvc.perform(get("/stream-failure").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL"));
    }

    @Test
    void domainFailureBeforeStreamCommitReturnsJsonWithMappedStatus() throws Exception {
        mvc.perform(get("/stream-domain-failure").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void validationFailureBeforeStreamCommitReturnsJson() throws Exception {
        mvc.perform(get("/stream-validation-failure").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void runtimeFailureAfterStreamCommitDoesNotAppendErrorBody() throws Exception {
        mvc.perform(get("/stream-failure").param("committed", "true")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string("data: ready\n\n"));
    }

    @Test
    void ordinaryIoFailureStillReturnsInternalError() throws Exception {
        mvc.perform(get("/io-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"));
    }

    @RestController
    static class FailingController {
        @GetMapping("/localized-disconnect")
        void localized(HttpServletResponse response,
                       @RequestParam(defaultValue = "false") boolean committed)
                throws IOException {
            prepareStream(response, committed);
            throw new IOException("你的主机中的软件中止了一个已建立的连接。");
        }

        @GetMapping("/stream-failure")
        void streamFailure(HttpServletResponse response,
                           @RequestParam(defaultValue = "false") boolean committed)
                throws IOException {
            prepareStream(response, committed);
            throw new IllegalStateException("stream service failed");
        }

        @GetMapping("/stream-domain-failure")
        void streamDomainFailure(HttpServletResponse response) throws IOException {
            prepareStream(response, false);
            throw new DomainException(
                    ErrorCodes.NOT_FOUND, "任务不存在");
        }

        @GetMapping("/stream-validation-failure")
        void streamValidationFailure(HttpServletResponse response) throws Exception {
            prepareStream(response, false);
            throw new MissingServletRequestParameterException("id", "UUID");
        }

        private static void prepareStream(HttpServletResponse response, boolean committed)
                throws IOException {
            response.setContentType("text/event-stream;charset=UTF-8");
            if (committed) {
                response.getWriter().write("data: ready\n\n");
                response.flushBuffer();
            }
        }

        @GetMapping("/aborted")
        void aborted(HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new org.apache.catalina.connector.ClientAbortException(new IOException("connection closed"));
        }

        @GetMapping("/disconnected")
        void disconnected(HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to flush",
                    new IOException("你的主机中的软件中止了一个已建立的连接。"));
        }

        @GetMapping("/io-error")
        void ioError() throws IOException {
            throw new IOException("disk read failed");
        }
    }
}

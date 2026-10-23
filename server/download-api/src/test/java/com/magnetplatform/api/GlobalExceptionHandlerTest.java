package com.magnetplatform.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
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
    void ordinaryIoFailureStillReturnsInternalError() throws Exception {
        mvc.perform(get("/io-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"));
    }

    @RestController
    static class FailingController {
        @GetMapping("/aborted")
        void aborted(jakarta.servlet.http.HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new org.apache.catalina.connector.ClientAbortException(new IOException("connection closed"));
        }

        @GetMapping("/disconnected")
        void disconnected(jakarta.servlet.http.HttpServletResponse response) throws IOException {
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

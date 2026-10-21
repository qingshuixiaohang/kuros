package com.kuros.kurosbackend.shared.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerContractTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ContractController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void 畸形JSON使用统一错误信封() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求体格式不正确"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void 参数类型错误使用统一错误信封() throws Exception {
        mockMvc.perform(get("/test/number").param("value", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求参数不正确"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void 缺少必填参数使用统一错误信封() throws Exception {
        mockMvc.perform(get("/test/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("缺少必需的请求参数"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void 字段校验失败返回字段错误映射() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.fieldErrors.value").value("不能为空"));
    }

    @Test
    void 未捕获异常返回通用错误且不泄露内部消息() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后重试"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @RestController
    static class ContractController {

        @PostMapping("/test/body")
        void body(@Valid @RequestBody TestBody body) {
        }

        @GetMapping("/test/number")
        void number(@RequestParam int value) {
        }

        @GetMapping("/test/required")
        void required(@RequestParam String value) {
        }

        @GetMapping("/test/error")
        void error() {
            throw new IllegalStateException("database password must not leak");
        }
    }

    record TestBody(@NotBlank(message = "不能为空") String value) {
    }
}

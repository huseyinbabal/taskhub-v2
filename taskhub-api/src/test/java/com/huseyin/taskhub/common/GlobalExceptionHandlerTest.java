package com.huseyin.taskhub.common;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    MockMvc mvc;

    @Test
    void unknownRouteReturns404ProblemDetailWithAllStandardFields() throws Exception {
        mvc.perform(get("/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.NOT_FOUND.toString()))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/unknown"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.MALFORMED_REQUEST.toString()))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void validationFailureReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"\", \"code\": \"toolong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.VALIDATION.toString()))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("title", "code")))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void notFoundExceptionMapsTo404() throws Exception {
        mvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.NOT_FOUND.toString()))
                .andExpect(jsonPath("$.detail").value("Task 42 not found"));
    }

    @Test
    void conflictExceptionMapsTo409() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ProblemTypes.CONFLICT.toString()))
                .andExpect(jsonPath("$.detail").value("Username already taken"));
    }

    @Test
    void forbiddenExceptionMapsTo403() throws Exception {
        mvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(ProblemTypes.FORBIDDEN.toString()))
                .andExpect(jsonPath("$.detail").value("Only the project owner can do this"));
    }

    @Test
    void unprocessableExceptionMapsTo422() throws Exception {
        mvc.perform(get("/test/unprocessable"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(ProblemTypes.UNPROCESSABLE.toString()))
                .andExpect(jsonPath("$.detail").value("Assignee must be a project member"));
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeakingInternals() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.INTERNAL.toString()))
                .andExpect(jsonPath("$.detail").value(not(containsString("secret-db-password"))));
    }

    record ValidatedRequest(
            @NotBlank String title, @Size(max = 3) String code) {}

    @RestController
    static class ThrowingController {

        @PostMapping("/test/validated")
        void validated(@Valid @RequestBody ValidatedRequest request) {}

        @GetMapping("/test/not-found")
        void notFound() {
            throw new NotFoundException("Task 42 not found");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictException("Username already taken");
        }

        @GetMapping("/test/forbidden")
        void forbidden() {
            throw new ForbiddenException("Only the project owner can do this");
        }

        @GetMapping("/test/unprocessable")
        void unprocessable() {
            throw new UnprocessableException("Assignee must be a project member");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("connection failed: secret-db-password");
        }
    }
}

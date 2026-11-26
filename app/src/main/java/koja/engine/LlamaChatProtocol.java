package koja.engine;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import koja.engine.LlmClient.ContextLengthExceededException;
import koja.engine.LlmClient.Message;
import koja.engine.LlmClient.OutOfMemoryException;
import koja.engine.LlmClient.Request;
import koja.engine.LlmClient.Response;
import koja.engine.LlmClient.Usage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code llama-server}의 OpenAI 호환 {@code /v1/chat/completions} 요청 직렬화,
 * 응답 파싱, 컨텍스트 초과·OOM·일시 오류 분류를 맡는다.
 */
final class LlamaChatProtocol {
    // 백엔드 초기화 실패("failed to allocate context" 등)를 OOM으로 오인하지 않도록 메모리 부족 전용 문구만 매칭한다.
    private static final List<String> OOM_TOKENS = List.of(
            "out of memory",
            "cudaerrormemoryallocation",
            "cuda_error_out_of_memory",
            "insufficient memory",
            "not enough memory",
            "cannot allocate memory",
            "unable to allocate memory",
            "kiogpucommandbuffercallbackerroroutofmemory",
            "vk_error_out_of_device_memory",
            "vk_error_out_of_host_memory");
    private static final List<String> CONTEXT_OVERFLOW_TOKENS = List.of(
            "exceed_context_size_error",
            "exceeds the available context size",
            "context_length_exceeded",
            "maximum context length");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LlamaChatProtocol() {}

    static byte[] buildRequestBody(Request request) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        for (Message msg : request.messages()) {
            ObjectNode m = messages.addObject();
            m.put("role", msg.role().wireName());
            m.put("content", msg.content());
        }
        root.put("temperature", request.temperature());
        root.put("max_tokens", request.maxTokens());
        root.put("stream", false);
        if (request.jsonSchema() != null) {
            JsonNode schemaNode;
            try {
                schemaNode = JSON.readTree(request.jsonSchema());
            } catch (JacksonException e) {
                throw new IllegalArgumentException("jsonSchema가 올바른 JSON이 아닙니다", e);
            }
            if (schemaNode == null || !schemaNode.isObject()) {
                throw new IllegalArgumentException("jsonSchema는 JSON 객체여야 합니다");
            }
            ObjectNode responseFormat = root.putObject("response_format");
            responseFormat.put("type", "json_schema");
            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.put("name", "response");
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", schemaNode);
        }
        return JSON.writeValueAsBytes(root);
    }

    static Response parseSuccessResponse(String body) throws IOException {
        if (body == null || body.isBlank()) {
            throw new IOException("llama-server 응답 본문이 비어 있습니다");
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new IOException("llama-server 응답 JSON을 읽을 수 없습니다", e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("llama-server 응답에 choices가 없습니다");
        }
        JsonNode first = choices.get(0);
        JsonNode contentNode = first.path("message").path("content");
        if (!contentNode.isString()) {
            throw new IOException("llama-server 응답에 message.content 문자열이 없습니다");
        }
        String finishReason = first.path("finish_reason").isString() ? first.path("finish_reason").asString() : null;
        JsonNode usageNode = root.path("usage");
        int promptTokens = Math.max(0, usageNode.path("prompt_tokens").asInt(0));
        int completionTokens = Math.max(0, usageNode.path("completion_tokens").asInt(0));
        return new Response(contentNode.asString(), new Usage(promptTokens, completionTokens), finishReason);
    }

    static IOException classifyHttpError(int status, String body, boolean serverLogsHaveOom) {
        String errorType = "";
        String errorMessage = body == null ? "" : body.trim();
        int promptTokens = -1;
        int contextSize = -1;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = JSON.readTree(body);
                JsonNode err = root.path("error");
                if (err.isObject()) {
                    errorType = err.path("type").asString("");
                    if (err.path("message").isString()) {
                        errorMessage = err.path("message").asString();
                    }
                    if (err.path("n_prompt_tokens").isIntegralNumber()) {
                        promptTokens = err.path("n_prompt_tokens").asInt();
                    }
                    if (err.path("n_ctx").isIntegralNumber()) {
                        contextSize = err.path("n_ctx").asInt();
                    }
                }
            } catch (JacksonException ignored) {
                // JSON이 아닌 오류 본문은 원문 문자열을 그대로 사용한다.
            }
        }
        String combined = errorType + " " + errorMessage;
        if (isContextOverflowText(combined)) {
            return new ContextLengthExceededException(
                    "프롬프트가 컨텍스트 크기를 초과했습니다(HTTP " + status + "): " + errorMessage,
                    promptTokens,
                    contextSize);
        }
        if (isOutOfMemoryText(combined) || serverLogsHaveOom) {
            return new OutOfMemoryException("llama-server 메모리 부족(HTTP " + status + "): " + errorMessage);
        }
        return new IOException("llama-server 요청 실패(HTTP " + status + "): " + errorMessage);
    }

    static boolean isRetryableStatus(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    static boolean isOutOfMemoryText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : OOM_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    static boolean isContextOverflowText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : CONTEXT_OVERFLOW_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }
}

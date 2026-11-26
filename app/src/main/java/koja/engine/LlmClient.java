package koja.engine;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** 5단계 구조화·7단계 번역용 LLM 클라이언트 계약(D-049, D-053~D-055). */
public interface LlmClient extends Engine {
    /** 대화 메시지 역할. */
    enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant");

        private final String wireName;

        Role(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /** LLM 요청 메시지 한 건. */
    record Message(Role role, String content) {
        public Message {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * LLM 호출 요청.
     * {@code jsonSchema}가 null이 아니면 응답이 해당 JSON 스키마를 따르도록 강제한다.
     */
    record Request(List<Message> messages, String jsonSchema, double temperature, int maxTokens) {
        public static final double DEFAULT_TEMPERATURE = 0.0;
        public static final int DEFAULT_MAX_TOKENS = 4096;

        public Request {
            Objects.requireNonNull(messages, "messages");
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("messages는 비어 있을 수 없습니다");
            }
            for (Message message : messages) {
                Objects.requireNonNull(message, "message");
            }
            messages = List.copyOf(messages);
            if (jsonSchema != null && jsonSchema.isBlank()) {
                throw new IllegalArgumentException("jsonSchema가 비어 있습니다");
            }
            if (!Double.isFinite(temperature) || temperature < 0.0) {
                throw new IllegalArgumentException("temperature는 0 이상의 유한수여야 합니다: " + temperature);
            }
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens는 양수여야 합니다: " + maxTokens);
            }
        }

        public static Request of(String systemPrompt, String userPrompt) {
            return new Request(systemAndUser(systemPrompt, userPrompt), null, DEFAULT_TEMPERATURE, DEFAULT_MAX_TOKENS);
        }

        public static Request withSchema(String systemPrompt, String userPrompt, String jsonSchema, int maxTokens) {
            Objects.requireNonNull(jsonSchema, "jsonSchema");
            return new Request(systemAndUser(systemPrompt, userPrompt), jsonSchema, DEFAULT_TEMPERATURE, maxTokens);
        }

        public static Request withSchema(List<Message> messages, String jsonSchema, int maxTokens) {
            Objects.requireNonNull(jsonSchema, "jsonSchema");
            return new Request(messages, jsonSchema, DEFAULT_TEMPERATURE, maxTokens);
        }

        private static List<Message> systemAndUser(String systemPrompt, String userPrompt) {
            Objects.requireNonNull(userPrompt, "userPrompt");
            if (systemPrompt == null || systemPrompt.isBlank()) {
                return List.of(new Message(Role.USER, userPrompt));
            }
            return List.of(new Message(Role.SYSTEM, systemPrompt), new Message(Role.USER, userPrompt));
        }
    }

    /** 토큰 사용량. 서버가 반환하지 않으면 0이다. */
    record Usage(int promptTokens, int completionTokens) {
        public static final Usage EMPTY = new Usage(0, 0);

        public Usage {
            if (promptTokens < 0 || completionTokens < 0) {
                throw new IllegalArgumentException("토큰 수는 음수일 수 없습니다");
            }
        }
    }

    /** LLM 응답 본문과 메타데이터. */
    record Response(String content, Usage usage, String finishReason) {
        public Response {
            Objects.requireNonNull(content, "content");
            usage = usage == null ? Usage.EMPTY : usage;
        }
    }

    /** 입력 프롬프트가 모델 컨텍스트 길이를 초과했을 때 발생한다. 같은 요청을 재시도하지 않는다. */
    class ContextLengthExceededException extends IOException {
        private final int promptTokens;
        private final int contextSize;

        public ContextLengthExceededException(String message, int promptTokens, int contextSize) {
            super(message);
            this.promptTokens = promptTokens;
            this.contextSize = contextSize;
        }

        /** 알 수 없으면 -1. */
        public int promptTokens() {
            return promptTokens;
        }

        /** 알 수 없으면 -1. */
        public int contextSize() {
            return contextSize;
        }
    }

    /** GPU VRAM 또는 시스템 메모리 부족(OOM)으로 모델 로드·추론에 실패했을 때 발생한다. */
    class OutOfMemoryException extends IOException {
        public OutOfMemoryException(String message) {
            super(message);
        }

        public OutOfMemoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** JSON 스키마 요청 응답이 {@code maxTokens} 한계({@code finish_reason: "length"})로 잘렸을 때 발생한다. */
    class OutputTruncatedException extends IOException {
        private final String partialContent;
        private final Usage usage;

        public OutputTruncatedException(String message, String partialContent, Usage usage) {
            super(message);
            this.partialContent = partialContent == null ? "" : partialContent;
            this.usage = usage == null ? Usage.EMPTY : usage;
        }

        public String partialContent() {
            return partialContent;
        }

        public Usage usage() {
            return usage;
        }
    }

    Response complete(Request request) throws IOException, InterruptedException;

    default String complete(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        return complete(Request.of(systemPrompt, userPrompt)).content();
    }
}

package koja.engine;

import koja.runtime.ModelStore;

/**
 * 로컬 {@code llama-server}용 고정 모델 명세(D-044, D-046).
 * 기본값은 Gemma 4 E4B(8GB VRAM 여유), 26B-A4B는 사용자가 고르는 선택 모델이다.
 */
public enum LlamaModel {
    GEMMA_4_E4B(
            "gemma-4-e4b",
            "Gemma 4 E4B (Q4_K_M, 기본)",
            ModelStore.ModelSpec.huggingFace(
                    "llm/gemma-4-E4B-it-Q4_K_M.gguf",
                    "unsloth/gemma-4-E4B-it-GGUF",
                    "bfc15c382204943c3a8fff0c750b94ae2364d7a3",
                    "gemma-4-E4B-it-Q4_K_M.gguf",
                    4_977_171_584L,
                    "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87")),
    GEMMA_4_26B(
            "gemma-4-26b-a4b",
            "Gemma 4 26B-A4B (UD-Q4_K_M, 선택)",
            ModelStore.ModelSpec.huggingFace(
                    "llm/gemma-4-26B-A4B-it-UD-Q4_K_M.gguf",
                    "unsloth/gemma-4-26B-A4B-it-GGUF",
                    "c099eb48e663fd284577b04978a94ffccb261841",
                    "gemma-4-26B-A4B-it-UD-Q4_K_M.gguf",
                    16_947_541_728L,
                    "f2c28b3dc4776931ac6f879e11f203dec637ea0f14267a86ec8f6165f63f293f"));

    public static final LlamaModel DEFAULT = GEMMA_4_E4B;

    private final String id;
    private final String displayName;
    private final ModelStore.ModelSpec spec;

    LlamaModel(String id, String displayName, ModelStore.ModelSpec spec) {
        this.id = id;
        this.displayName = displayName;
        this.spec = spec;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public ModelStore.ModelSpec spec() {
        return spec;
    }
}

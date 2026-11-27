package koja.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import koja.core.Project.VideoType;
import koja.core.StageStore;
import koja.core.StageStore.RowsResult;
import koja.core.StageStore.SpeakerRole;
import koja.core.StageStore.Unit;
import koja.core.StageStore.UnitRow;
import koja.core.StructuredRows;
import koja.core.StructuredRows.Chunk;
import koja.core.StructuredRows.Settled;
import koja.core.StructuringValidator;
import koja.core.StructuringValidator.Action;
import koja.core.StructuringValidator.Items;
import koja.engine.LlmClient.ContextLengthExceededException;
import koja.engine.LlmClient.Message;
import koja.engine.LlmClient.OutputTruncatedException;
import koja.engine.LlmClient.Request;
import koja.engine.LlmClient.Role;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 5단계 구조화(D-053~D-055): 1단계 영상 분석(videoType·화자 역할) → 2단계 청크(최대 24단위·3분, 앞뒤 6단위·30초)마다
 * 평탄 1:1 스키마로 묻고, 코드 검증(절대 규칙 1)에 틀리면 최신 오류만 덧붙여 3번까지 다시 묻고, 그래도 틀리면 대체 청크.
 * 청크 끝에 걸친 행은 다음 청크에서 다시 묻는다(settle).
 */
public final class Structurer {
    static final int CHUNK_UNITS = 24;
    static final long CHUNK_MS = 180_000;
    static final int CTX_UNITS = 6;
    static final long CTX_MS = 30_000;
    static final int TRIES = 3;
    static final int ANALYZE_TRIES = 2;
    static final int MAX_TOKENS = 4096;
    private static final int ANALYZE_MAX_TOKENS = 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 실행 통계. 평가·로그용이다. */
    public record Stats(int chunks, int firstTry, int retries, int fallbackChunks) {}

    public record Result(RowsResult rows, Stats stats) {}

    private Structurer() {}

    /**
     * fixedType이 null이 아니면(사용자가 지정한 영상 유형) 1단계 결과의 유형 대신 쓴다. 화자 역할은 그래도 추정한다.
     * 결과는 StageStore 규칙으로 다시 검증한 뒤 돌려준다.
     */
    public static Result run(LlmClient llm, List<Unit> units, VideoType fixedType, DoubleConsumer progress)
            throws IOException, InterruptedException {
        if (units.isEmpty()) {
            return empty(llm.name(), fixedType);
        }
        int[] st = new int[4];
        Analysis a = analyze(llm, units);
        VideoType type = fixedType != null ? fixedType : a.type();
        String table = StructuringPrompts.speakerTable(units, a.roles());
        Map<Integer, Unit> byId = units.stream().collect(Collectors.toMap(Unit::id, Function.identity()));
        List<UnitRow> rows = new ArrayList<>();
        List<Integer> ignored = new ArrayList<>();
        Map<Integer, List<String>> doneKind = new HashMap<>();
        int lo = 0;
        while (lo < units.size()) {
            if (Thread.interrupted()) {
                throw new InterruptedException("취소");
            }
            progress.accept((double) lo / units.size());
            int hi = lo;
            // 3분은 청크 첫 단위 시작부터 마지막 단위 끝까지다(D-055).
            while (hi < units.size() && hi - lo < CHUNK_UNITS
                    && units.get(hi).endMs() <= units.get(lo).startMs() + CHUNK_MS) {
                hi++;
            }
            hi = Math.max(hi, lo + 1);
            int fallbacks = st[3];
            Chunk chunk = ask(llm, units, byId, lo, hi, doneKind, table, type, st);
            st[0]++;
            // 대체 청크는 끝 행을 다시 묻지 않고 통째로 확정한다. 다음 청크가 그 단위를 무시하면 대체 행이 사라진다(D-032).
            boolean fellBack = st[3] > fallbacks;
            Settled s = StructuredRows.settle(chunk, units.get(lo).id(), hi == units.size() || fellBack);
            for (UnitRow r : s.rows()) {
                rows.add(r);
                for (int id : r.unitIds()) {
                    doneKind.computeIfAbsent(id, k -> new ArrayList<>()).add(r.kind().name());
                }
            }
            for (int id : s.ignored()) {
                ignored.add(id);
                doneKind.put(id, List.of("무시"));
            }
            if (s.cut() == null) {
                lo = hi;
            } else {
                while (units.get(lo).id() < s.cut()) {
                    lo++;
                }
            }
        }
        List<UnitRow> named = new ArrayList<>(rows.size());
        for (UnitRow r : rows) {
            named.add(new UnitRow("r" + (named.size() + 1), r.kind(), r.unitIds(), r.flags()));
        }
        RowsResult result = new RowsResult(StageStore.CURRENT_VERSION, llm.name(), type, a.roles(), units,
                List.copyOf(named), ignored.stream().sorted().toList());
        String error = StructuringValidator.checkRows(result.rows(), result.ignoredUnitIds(), units, type);
        if (error != null) {
            // 청크마다 검증했으므로 여기 오면 합치는 코드의 버그다. 틀린 행을 저장하지 않는다.
            throw new IllegalStateException("구조화 결과를 합친 뒤 검증 실패: " + error);
        }
        progress.accept(1);
        return new Result(result, new Stats(st[0], st[1], st[2], st[3]));
    }

    /** 발언이 없는 영상. LLM을 부르지 않는다. */
    public static Result empty(String engine, VideoType fixedType) {
        return new Result(new RowsResult(StageStore.CURRENT_VERSION, engine, fixedType != null ? fixedType : VideoType.MIXED,
                Map.of(), List.of(), List.of(), List.of()), new Stats(0, 0, 0, 0));
    }

    record Analysis(VideoType type, Map<String, SpeakerRole> roles) {}

    /** 1단계. 두 번 다 틀리면 MIXED + 역할 미확정(모르면 비움, 절대 규칙 2). */
    static Analysis analyze(LlmClient llm, List<Unit> units) throws IOException, InterruptedException {
        List<String> speakers = StructuringPrompts.speakers(units);
        if (speakers.isEmpty()) {
            return new Analysis(VideoType.MIXED, Map.of());
        }
        Request request = Request.withSchema(StructuringPrompts.analyzeMessages(units),
                StructuringPrompts.analyzeSchema(speakers), ANALYZE_MAX_TOKENS);
        for (int i = 0; i < ANALYZE_TRIES; i++) {
            String content;
            try {
                content = llm.complete(request).content();
            } catch (OutputTruncatedException e) {
                continue;
            } catch (ContextLengthExceededException e) {
                // 발췌는 길이가 정해져 있어 드물다(추정). 넘치면 다시 보내지 않고 미확정으로 진행한다(D-067).
                break;
            }
            Analysis a = parseAnalysis(content, speakers);
            if (a != null) {
                return a;
            }
        }
        Map<String, SpeakerRole> unknown = new LinkedHashMap<>();
        speakers.forEach(s -> unknown.put(s, SpeakerRole.UNKNOWN));
        return new Analysis(VideoType.MIXED, Map.copyOf(unknown));
    }

    private static Analysis parseAnalysis(String content, List<String> speakers) {
        try {
            JsonNode root = JSON.readTree(content);
            VideoType type = VideoType.valueOf(root.path("videoType").asString());
            Map<String, SpeakerRole> roles = new LinkedHashMap<>();
            for (JsonNode n : root.path("speakers")) {
                roles.put(n.path("id").asString(), SpeakerRole.valueOf(n.path("role").asString()));
            }
            int count = root.path("speakers").size();
            if (count != speakers.size() || !roles.keySet().equals(new HashSet<>(speakers))) {
                return null;
            }
            // "?"는 한 사람이 아니라 화자 미배정 발언 묶음이라 역할을 정하지 않는다(절대 규칙 2).
            roles.replace(StructuringPrompts.UNKNOWN_SPEAKER, SpeakerRole.UNKNOWN);
            return new Analysis(type, Map.copyOf(roles));
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Chunk ask(LlmClient llm, List<Unit> units, Map<Integer, Unit> byId, int lo, int hi,
            Map<Integer, List<String>> doneKind, String table, VideoType type, int[] st)
            throws IOException, InterruptedException {
        List<Unit> chunk = units.subList(lo, hi);
        List<Integer> ids = chunk.stream().map(Unit::id).toList();
        Set<Integer> dual = chunk.stream().filter(Unit::hasKoCandidate).map(Unit::id).collect(Collectors.toSet());
        List<Action> actions = StructuringValidator.actionsFor(type, !dual.isEmpty());
        String schema = StructuringPrompts.flatSchema(ids, actions);
        List<Message> base = StructuringPrompts.chunkMessages(units, lo, hi, doneKind, table, type, CTX_UNITS, CTX_MS);
        List<Message> messages = base;
        for (int attempt = 0; attempt < TRIES; attempt++) {
            String error;
            Chunk out = null;
            try {
                Items items = StructuringValidator.parseItems(
                        llm.complete(Request.withSchema(messages, schema, MAX_TOKENS)).content(), ids, dual, actions);
                error = items.error();
                if (error == null) {
                    out = StructuredRows.toRows(items.items(), byId);
                    error = StructuringValidator.checkRows(out.rows(), out.ignored(), ids, dual, type);
                }
            } catch (OutputTruncatedException e) {
                error = "출력이 길이 한계로 잘렸습니다.";
            } catch (ContextLengthExceededException e) {
                // 같은 요청은 다시 보내도 넘친다(D-067). 이 청크만 대체로 남기고 계속한다.
                break;
            }
            if (error == null) {
                st[1] += attempt == 0 ? 1 : 0;
                return out;
            }
            st[2] += attempt < TRIES - 1 ? 1 : 0;
            // 깨진 긴 JSON을 쌓지 않고 최신 오류와 정확한 id 목록만 덧붙인다(D-053).
            List<Message> retry = new ArrayList<>(base);
            retry.add(new Message(Role.USER, "이전 출력 검증 실패: " + error + "\n처리할 구간 ID 목록 " + ids
                    + " 를 빠짐없이 정확히 이 순서대로 다시 JSON 출력하세요."));
            messages = retry;
        }
        st[3]++;
        return StructuredRows.fallback(chunk);
    }
}

package koja.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import koja.core.Project.Flag;
import koja.core.Project.RowKind;
import koja.core.Project.VideoType;
import koja.core.Project.Word;
import koja.core.StageStore.SpeakerRole;
import koja.core.StageStore.Unit;
import koja.core.StageStore.UnitRow;
import koja.core.StructuringValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class StructurerTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    /** 요청 스키마를 보고 답한다. 분석 요청이면 analysis, 청크 요청이면 chunk(요청 id 목록). */
    static class FakeLlm implements LlmClient {
        final List<Request> requests = new ArrayList<>();
        final List<List<Integer>> chunks = new ArrayList<>();
        String analysis = "{\"videoType\":\"INTERVIEW_JA\",\"speakers\":[{\"id\":\"S1\",\"role\":\"INTERVIEWEE\"}]}";
        Function<List<Integer>, String> chunk = ids -> items(ids, "LINE", true);
        boolean closed;

        public String name() { return "fake-llm"; }

        public Response complete(Request request) throws IOException {
            requests.add(request);
            JsonNode schema = JSON.readTree(request.jsonSchema());
            if (schema.path("properties").has("videoType")) {
                return new Response(analysis, null, "stop");
            }
            List<Integer> ids = new ArrayList<>();
            schema.path("properties").path("items").path("items").path("properties").path("id").path("enum")
                    .forEach(n -> ids.add(n.asInt()));
            chunks.add(ids);
            return new Response(chunk.apply(ids), null, "stop");
        }

        public void close() { closed = true; }
    }

    static String items(List<Integer> ids, String action, boolean newRow) {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < ids.size(); i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":").append(ids.get(i)).append(",\"action\":\"").append(action)
                    .append("\",\"newRow\":").append(newRow).append(",\"flags\":[]}");
        }
        return sb.append("]}").toString();
    }

    static List<Unit> units(int n) {
        List<Unit> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            long t = i * 1_000L;
            out.add(new Unit(i, i, t, t + 900, "ko", "S1", "발언" + i, List.of(new Word(t, t + 900, "발언" + i)), null));
        }
        return out;
    }

    @Test
    void analyzesThenStructuresEveryUnitOnce() throws Exception {
        FakeLlm llm = new FakeLlm();
        List<Double> progress = new ArrayList<>();
        Structurer.Result r = Structurer.run(llm, units(3), null, progress::add);
        assertEquals(VideoType.INTERVIEW_JA, r.rows().videoType());
        assertEquals(SpeakerRole.INTERVIEWEE, r.rows().speakerRoles().get("S1"));
        assertEquals(List.of("r1", "r2", "r3"), r.rows().rows().stream().map(UnitRow::id).toList());
        assertEquals(new Structurer.Stats(1, 1, 0, 0), r.stats());
        assertEquals("fake-llm", r.rows().engine());
        assertEquals(1.0, progress.getLast());
        assertNull(StructuringValidator.checkRows(r.rows().rows(), r.rows().ignoredUnitIds(), r.rows().units(),
                r.rows().videoType()));
    }

    @Test
    void retriesWithLatestErrorThenAccepts() throws Exception {
        FakeLlm llm = new FakeLlm();
        int[] calls = { 0 };
        llm.chunk = ids -> calls[0]++ == 0 ? items(ids.subList(0, 1), "LINE", true) : items(ids, "LINE", true);
        Structurer.Result r = Structurer.run(llm, units(2), null, f -> {});
        assertEquals(new Structurer.Stats(1, 0, 1, 0), r.stats());
        List<LlmClient.Message> retry = llm.requests.getLast().messages();
        assertEquals(3, retry.size());
        assertTrue(retry.getLast().content().contains("빠짐 [2]"));
    }

    @Test
    void fallsBackToOneLinePerUnitAfterThreeFailures() throws Exception {
        FakeLlm llm = new FakeLlm();
        llm.chunk = ids -> "not json";
        Structurer.Result r = Structurer.run(llm, units(2), null, f -> {});
        assertEquals(new Structurer.Stats(1, 0, 2, 1), r.stats());
        assertEquals(Structurer.TRIES, llm.chunks.size());
        assertEquals(List.of(List.of(1), List.of(2)), r.rows().rows().stream().map(UnitRow::unitIds).toList());
        // 3번째 시도 뒤에는 쌓인 오류 대신 마지막 오류 하나만 붙는다.
        assertEquals(3, llm.requests.getLast().messages().size());
    }

    @Test
    void contextOverflowFallsBackWithoutRetry() throws Exception {
        FakeLlm llm = new FakeLlm() {
            @Override
            public Response complete(Request request) throws IOException {
                if (request.jsonSchema().contains("newRow")) {
                    throw new ContextLengthExceededException("넘침", 9_000, 8_192);
                }
                return super.complete(request);
            }
        };
        Structurer.Result r = Structurer.run(llm, units(2), null, f -> {});
        assertEquals(new Structurer.Stats(1, 0, 0, 1), r.stats());
    }

    @Test
    void chunksAtTwentyFourUnitsAndReasksTheLastRow() throws Exception {
        FakeLlm llm = new FakeLlm();
        // 모두 한 행으로 이어 붙이라는 답: 과병합 방지로 행이 나뉘고, 끝 행은 다음 청크에서 다시 묻는다.
        llm.chunk = ids -> items(ids, "LINE", false);
        Structurer.Result r = Structurer.run(llm, units(30), null, f -> {});
        assertEquals(Structurer.CHUNK_UNITS, llm.chunks.get(0).size());
        assertTrue(llm.chunks.get(1).getFirst() <= Structurer.CHUNK_UNITS);
        assertNull(StructuringValidator.checkRows(r.rows().rows(), r.rows().ignoredUnitIds(), r.rows().units(),
                r.rows().videoType()));
        String second = llm.requests.get(2).messages().get(1).content();
        assertTrue(second.contains("앞 문맥(처리됨, 출력하지 말 것)"));
    }

    @Test
    void userVideoTypeWinsAndFreeVideoHasNoQuestionActions() throws Exception {
        FakeLlm llm = new FakeLlm();
        Structurer.Result r = Structurer.run(llm, units(2), VideoType.FREE, f -> {});
        assertEquals(VideoType.FREE, r.rows().videoType());
        assertFalse(llm.requests.getLast().jsonSchema().contains("QUESTION"));
        assertTrue(llm.requests.getLast().messages().getFirst().content().contains("FREE"));
    }

    @Test
    void brokenAnalysisFallsBackToMixedAndUnknownRoles() throws Exception {
        FakeLlm llm = new FakeLlm();
        llm.analysis = "{\"videoType\":\"INTERVIEW_JA\",\"speakers\":[{\"id\":\"S9\",\"role\":\"INTERVIEWEE\"}]}";
        Structurer.Result r = Structurer.run(llm, units(1), null, f -> {});
        assertEquals(VideoType.MIXED, r.rows().videoType());
        assertEquals(SpeakerRole.UNKNOWN, r.rows().speakerRoles().get("S1"));
        assertEquals(Structurer.ANALYZE_TRIES + 1, llm.requests.size());
    }

    @Test
    void bothJaLineIsOfferedOnlyWithKoCandidateAndUnknownSpeakerIsFlagged() throws Exception {
        FakeLlm llm = new FakeLlm();
        List<Unit> us = List.of(new Unit(1, 1, 0, 900, "ja", null, "質問", List.of(new Word(0, 900, "質問")),
                new koja.core.Project.KoCandidate("답변입니다", List.of(new Word(0, 900, "답변입니다")))));
        llm.chunk = ids -> items(ids, "BOTH_JA_LINE", true);
        Structurer.Result r = Structurer.run(llm, us, null, f -> {});
        assertEquals(List.of(RowKind.QUESTION_JA, RowKind.LINE), r.rows().rows().stream().map(UnitRow::kind).toList());
        assertEquals(List.of(Flag.SPEAKER_UNSURE), r.rows().rows().get(1).flags());
        assertTrue(llm.requests.get(0).messages().get(1).content().contains("?:"));
    }
}

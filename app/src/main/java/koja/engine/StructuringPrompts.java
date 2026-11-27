package koja.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import koja.core.Project.Flag;
import koja.core.Project.VideoType;
import koja.core.StageStore.SpeakerRole;
import koja.core.StageStore.Unit;
import koja.core.StructuringValidator.Action;
import koja.engine.LlmClient.Message;
import koja.engine.LlmClient.Role;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 5단계 구조화 프롬프트와 JSON 스키마(D-053). 문구는 poc/structure.py 확정판(평탄 v3)과 같다. 바꿀 때는 eval/로 다시 잰다.
 * LLM에는 단위 id·시각·화자·언어·텍스트를 보여 주고, 시간·원문 필드는 받지 않는다(절대 규칙 1).
 */
final class StructuringPrompts {
    /** 화자를 모를 때 프롬프트에 쓰는 표시. */
    static final String UNKNOWN_SPEAKER = "?";

    static final String ANALYZE_SYSTEM = """
            당신은 게임(e스포츠) 영상의 음성 인식 요약과 대화 발췌를 보고 영상 유형과 화자 역할을 판별합니다.
            화자별 총 발화 시간, 한국어/일본어 시간 비율, 앞뒤 화자 전환, 실제 연속 대화 발췌를 함께 보고 판별하세요.

            1. videoType:
            - INTERVIEW_JA: 일본인 인터뷰어가 일본어로 질문하고 한국인 출연자가 한국어로 답변하는 인터뷰(통역사가 함께 있을 수 있음)
            - INTERVIEW_KO: 한국인 인터뷰어와 한국인 출연자의 인터뷰
            - FREE: 질문/답변 구조 없이 리뷰·해설·자유 대화로 진행되는 영상
            - MIXED: 인터뷰와 자유 대화가 섞인 영상

            2. 각 화자의 role:
            - INTERVIEWEE: 한국어로 답변·설명하는 선수·출연자. 보통 한국어 시간 비율이 압도적으로 높습니다(75% 이상).
            - INTERPRETER: 인터뷰어와 답변자 사이에서 통역하는 통역사. 다음 특징 중 하나 이상을 보입니다:
              * 한국어와 일본어를 모두 상당 비율(각 20% 이상) 말함('[한·일 혼합 화자]' 표시)
              * 연속 대화에서 일본어 질문 직후 그 질문을 한국어로 옮기고, 한국어 답변 직후 그 답변을 일본어로 옮김
              * 통역사가 한국어를 많이 말하더라도 일본어 통역을 겸하고 질문/답변을 반복하면 INTERVIEWEE가 아니라 반드시 INTERPRETER입니다!
            - INTERVIEWER_JA: 일본어로 새 주제를 질문하는 인터뷰어(일본어 시간 비율이 높고 질문 문장이 많음)
            - INTERVIEWER_KO: 한국어로 질문하는 인터뷰어
            - STAFF: 촬영 관계자·잡담(짧은 안내나 현장 대화)
            - UNKNOWN: '?'(화자 미배정)이거나 구간이 적어 판단하기 어려움

            화자 목록의 모든 화자 ID를 입력 순서대로 빠짐없이 한 번씩 출력하세요. 출력은 지정한 JSON만.""";

    static final String CHUNK_SYSTEM = """
            당신은 게임(e스포츠) 영상의 음성 인식 구간을 자막 편집용 시트 행으로 분류·구성합니다.
            각 줄 형식: [구간ID] 시각 화자 언어: 텍스트. 언어가 ko가 아닌 구간은 같은 소리를 한국어로 다시 인식한 'ko후보'가 함께 있습니다.
            주의: 일본어 음성을 한국어로 강제 인식한 'ko후보'는 발음만 비슷하고 뜻이 통하지 않는 깨진 한국어인 경우가 많습니다. 텍스트가 자연스러운 일본어이고 ko후보가 부자연스러우면 순수 일본어 발화입니다. 반대로 텍스트가 깨지고 ko후보가 자연스러운 한국어 문장이면 한국어 발화입니다.
            화자 역할 표는 참고 단서이며 화자 구분이 일부 틀릴 수 있으므로 앞뒤 문맥과 실제 내용을 함께 보고 판단하세요.

            '처리할 구간'의 모든 구간ID를 입력과 정확히 같은 순서로 하나씩 출력하세요(앞·뒤 문맥은 참고만 하고 출력하지 마세요).
            각 항목 필드:
            - id: 입력 구간ID 그대로
            - action: 아래 규칙에 따라 선택
            - newRow: true이면 새 행 시작, false이면 바로 앞 구간과 같은 행으로 이어 붙임
              * LINE·QUESTION_KO: 각 구간은 이미 자막 한 행 길이의 문장·절 단위이므로 기본값은 반드시 true다. 바로 앞 구간이 10자 미만의 아주 짧은 미완성 조각(주어·접속어만 잘림 등)일 때만 false로 이어 붙인다.
              * QUESTION_JA: 한 질문이 여러 구간에 이어지면 첫 구간만 true, 이어지는 같은 질문 구간은 false로 한 행에 묶는다.
              * 화자나 action이 바뀌거나 IGNORE 다음이면 반드시 true.
            - flags: 보통 []. 화자 구분이 문맥과 어긋나 보이면 "SPEAKER_UNSURE", 전사가 깨지거나 들리지 않아 확인이 필요하면 "TEXT_UNSURE"

            출력은 지정한 JSON만.""";

    private static final String RULES_FREE = """
            [영상 유형: FREE (자유 발언형)]
            - LINE: 한국어 발언(질문 영역을 만들지 않는다).
            - IGNORE: 의미 없는 잡음·비한국어 잡담. 판단이 애매한 한국어 발언은 무시하지 말고 LINE으로 남긴다.""";

    private static final String RULES_INTERVIEW_KO = """
            [영상 유형: INTERVIEW_KO (한국인 인터뷰어)]
            - QUESTION_KO: 한국인 인터뷰어의 질문.
            - LINE: 답변자(선수/출연자)의 한국어 답변.
            - IGNORE: 관계자 잡담, 의미 없는 소리. 애매한 한국어 발언은 무시하지 말고 LINE으로 남긴다.""";

    private static final String RULES_INTERVIEW_JA = """
            [영상 유형: INTERVIEW_JA (일본인 인터뷰어 + 한국인 답변자)]
            - LINE: 답변자(INTERVIEWEE)의 한국어 발언.
              * 빠짐없음이 최우선이다. 답변자(INTERVIEWEE)로 추정된 화자의 구간은 언어 태그가 ja/en으로 잘못 붙었더라도 ko후보가 자연스러운 한국어 문장이면 반드시 LINE으로 남긴다(답변자 화자 구간은 명백한 무의미 잡음 외에는 IGNORE 금지).
              * 반대로 인터뷰어·통역사의 순수 일본어 발화(ko후보가 깨진 발음 음차일 뿐인 구간)는 LINE으로 넣지 않는다.
            - QUESTION_JA: 일본인 인터뷰어(INTERVIEWER_JA)가 새 주제를 묻는 일본어 질문.
              * 인터뷰어의 정상적인 일본어 질문에 ko후보가 붙어 있어도 LINE이나 BOTH_JA_LINE으로 넣지 말고 QUESTION_JA만 쓴다.
              * 통역사(INTERPRETER)가 바로 앞 한국어 답변을 일본어로 옮기는 말과 짧은 맞장구(はい, なるほど, ありがとうございます, そうですね, うん 등 질문이 아닌 반응)는 QUESTION_JA가 아니라 반드시 IGNORE다!
            - BOTH_JA_LINE: 매우 드문 예외. 'ko후보'가 있는 한 구간 안에 인터뷰어의 일본어 질문과 답변자의 실제 한국어 답변이 둘 다 함께 들어 있을 때만 쓴다(순수 일본어 질문이나 순수 한국어 답변에는 절대 쓰지 말 것, ko후보 없는 구간 금지).
            - IGNORE:
              * 통역사(INTERPRETER)의 통역: 바로 앞 일본어 질문을 한국어로 옮겨 주는 말, 바로 앞 한국어 답변을 일본어로 옮겨 주는 말.
              * 관계자(STAFF) 잡담, 짧은 맞장구·추임새·의미 없는 소리.
              * 단, 통역사 화자로 붙었더라도 앞 질문의 통역이 아니라 답변자의 실제 답변 내용으로 보이면 IGNORE하지 말고 LINE으로 남긴다.""";

    private static final String RULES_MIXED = RULES_INTERVIEW_JA.replace(
            "[영상 유형: INTERVIEW_JA (일본인 인터뷰어 + 한국인 답변자)]", "[영상 유형: MIXED (혼합형)]")
            + "\n- QUESTION_KO: 인터뷰 구간에서 한국인 인터뷰어의 한국어 질문이 있을 때만 사용."
            + "\n- 자유 발언·해설·토론 구간: 인터뷰가 아닌 자유 발언 구간에서는 답변자 외 화자의 한국어 해설·토론도 IGNORE하지 말고"
            + " 모두 LINE으로 남긴다(질문 영역을 억지로 만들지 않는다).";

    private static final Map<SpeakerRole, String> ROLE_KO = Map.of(
            SpeakerRole.INTERVIEWER_JA, "일본인 인터뷰어(질문자)",
            SpeakerRole.INTERVIEWER_KO, "한국인 인터뷰어(질문자)",
            SpeakerRole.INTERVIEWEE, "답변자(선수/출연자)",
            SpeakerRole.INTERPRETER, "통역사",
            SpeakerRole.STAFF, "관계자",
            SpeakerRole.UNKNOWN, "역할 미확정");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StructuringPrompts() {}

    static String rules(VideoType type) {
        return switch (type) {
            case FREE -> RULES_FREE;
            case INTERVIEW_KO -> RULES_INTERVIEW_KO;
            case INTERVIEW_JA -> RULES_INTERVIEW_JA;
            case MIXED -> RULES_MIXED;
        };
    }

    static String speaker(Unit u) {
        return u.speakerId() == null ? UNKNOWN_SPEAKER : u.speakerId();
    }

    /** 화자별 시간·언어 비율. 발화 시간이 긴 화자부터(같으면 먼저 나온 화자). */
    private record Stat(String speaker, int count, long ms, long koMs, long jaMs) {
        String ratios() {
            return "ko " + koMs * 100 / ms + "%, ja " + jaMs * 100 / ms + "%";
        }
    }

    private static List<Stat> stats(List<Unit> units) {
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (Unit u : units) {
            long[] a = acc.computeIfAbsent(speaker(u), k -> new long[4]);
            long d = Math.max(1, u.endMs() - u.startMs());
            a[0]++;
            a[1] += d;
            if ("ko".equals(u.lang())) {
                a[2] += d;
            } else if ("ja".equals(u.lang())) {
                a[3] += d;
            }
        }
        List<Stat> out = new ArrayList<>();
        acc.forEach((k, a) -> out.add(new Stat(k, (int) a[0], a[1], a[2], a[3])));
        out.sort(Comparator.comparingLong(Stat::ms).reversed());
        return out;
    }

    static List<String> speakers(List<Unit> units) {
        return stats(units).stream().map(Stat::speaker).toList();
    }

    /** 1단계 영상 분석 입력: 화자별 요약, 직전·직후 화자, 대표 발화, 영상 25%·60% 지점 연속 대화 발췌(D-053). */
    static List<Message> analyzeMessages(List<Unit> units) {
        Map<String, Map<String, Integer>> prev = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> next = new LinkedHashMap<>();
        for (int i = 1; i < units.size(); i++) {
            String a = speaker(units.get(i - 1));
            String b = speaker(units.get(i));
            if (!a.equals(b)) {
                prev.computeIfAbsent(b, k -> new LinkedHashMap<>()).merge(a, 1, Integer::sum);
                next.computeIfAbsent(a, k -> new LinkedHashMap<>()).merge(b, 1, Integer::sum);
            }
        }
        List<String> lines = new ArrayList<>();
        for (Stat s : stats(units)) {
            String bilingual = s.koMs() * 100 / s.ms() >= 20 && s.jaMs() * 100 / s.ms() >= 20 ? " [한·일 혼합 화자]" : "";
            lines.add("- " + s.speaker() + ": " + s.ms() / 1000 + "초(구간 " + s.count() + "개), " + s.ratios() + bilingual
                    + ", 직전 [" + top2(prev.get(s.speaker())) + "], 직후 [" + top2(next.get(s.speaker())) + "]");
            for (String lang : List.of("ko", "ja")) {
                units.stream().filter(u -> speaker(u).equals(s.speaker()) && lang.equals(u.lang()) && u.text().length() >= 6)
                        .max(Comparator.comparingInt(u -> u.text().length()))
                        .ifPresent(u -> lines.add("    예시[" + lang + "]: " + head(u.text(), 60)));
            }
        }
        int n = units.size();
        List<int[]> ranges = new ArrayList<>();
        if (n <= 24) {
            ranges.add(new int[] { 0, n });
        } else {
            int a0 = n / 4;
            int a1 = Math.min(n, n / 4 + 12);
            int b0 = n * 3 / 5;
            int b1 = Math.min(n, n * 3 / 5 + 12);
            if (a1 >= b0) {
                ranges.add(new int[] { a0, Math.max(a1, b1) });
            } else {
                ranges.add(new int[] { a0, a1 });
                ranges.add(new int[] { b0, b1 });
            }
        }
        for (int r = 0; r < ranges.size(); r++) {
            lines.add(ranges.size() > 1 ? "\n연속 대화 발췌 " + (r + 1) + "(시간 순):" : "\n연속 대화 발췌(시간 순):");
            for (Unit u : units.subList(ranges.get(r)[0], ranges.get(r)[1])) {
                lines.add("  " + mmss(u.startMs()) + " " + speaker(u) + "(" + u.lang() + "): " + head(u.text(), 65));
            }
        }
        String user = "영상 전체 구간 수: " + n + "개\n\n화자별 요약:\n" + String.join("\n", lines);
        return List.of(new Message(Role.SYSTEM, ANALYZE_SYSTEM), new Message(Role.USER, user));
    }

    private static String top2(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "없음";
        }
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(2)
                .map(e -> e.getKey() + "(" + e.getValue() + ")").collect(Collectors.joining(", "));
    }

    /** 청크 프롬프트의 화자 표(시간 비율 + 1단계 역할 추정). */
    static String speakerTable(List<Unit> units, Map<String, SpeakerRole> roles) {
        List<String> out = new ArrayList<>();
        for (Stat s : stats(units)) {
            SpeakerRole role = roles.get(s.speaker());
            out.add(s.speaker() + ": " + s.ms() / 1000 + "초(구간 " + s.count() + "개), " + s.ratios()
                    + (role == null ? "" : ", 추정 역할: " + ROLE_KO.get(role)));
        }
        return String.join("\n", out);
    }

    static String unitLine(Unit u) {
        String t = "[" + u.id() + "] " + mmss(u.startMs()) + " " + speaker(u) + " " + u.lang() + ": " + u.text();
        return u.hasKoCandidate() ? t + " | ko후보: " + u.ko().text() : t;
    }

    /** units[lo, hi)가 처리할 단위. 앞뒤 최대 ctxUnits개·ctxMs 안 단위를 문맥으로 붙인다(D-055). */
    static List<Message> chunkMessages(List<Unit> units, int lo, int hi, Map<Integer, List<String>> doneKind,
            String table, VideoType type, int ctxUnits, long ctxMs) {
        long t0 = units.get(lo).startMs();
        long t1 = units.get(hi - 1).endMs();
        List<String> parts = new ArrayList<>();
        parts.add("화자 표(영상 전체):\n" + table);
        List<String> before = units.subList(Math.max(0, lo - ctxUnits), lo).stream().filter(u -> u.startMs() >= t0 - ctxMs)
                .map(u -> unitLine(u) + "  → " + String.join("+", doneKind.getOrDefault(u.id(), List.of()))).toList();
        if (!before.isEmpty()) {
            parts.add("앞 문맥(처리됨, 출력하지 말 것):\n" + String.join("\n", before));
        }
        parts.add("처리할 구간:\n" + units.subList(lo, hi).stream().map(StructuringPrompts::unitLine)
                .collect(Collectors.joining("\n")));
        List<String> after = units.subList(hi, Math.min(units.size(), hi + ctxUnits)).stream()
                .filter(u -> u.startMs() <= t1 + ctxMs).map(StructuringPrompts::unitLine).toList();
        if (!after.isEmpty()) {
            parts.add("뒤 문맥(출력하지 말 것):\n" + String.join("\n", after));
        }
        return List.of(new Message(Role.SYSTEM, CHUNK_SYSTEM + "\n\n" + rules(type)),
                new Message(Role.USER, String.join("\n\n", parts)));
    }

    static String analyzeSchema(List<String> speakers) {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        root.putArray("required").add("videoType").add("speakers");
        ObjectNode props = root.putObject("properties");
        enumOf(props.putObject("videoType"), "string", names(VideoType.values()));
        ObjectNode arr = props.putObject("speakers");
        arr.put("type", "array").put("minItems", speakers.size()).put("maxItems", speakers.size());
        ObjectNode item = arr.putObject("items");
        item.put("type", "object");
        item.putArray("required").add("id").add("role");
        ObjectNode ip = item.putObject("properties");
        enumOf(ip.putObject("id"), "string", speakers);
        enumOf(ip.putObject("role"), "string", names(SpeakerRole.values()));
        return JSON.writeValueAsString(root);
    }

    /** 청크 단위와 1:1 고정 순서 배열(minItems == maxItems == 단위 수, D-053). */
    static String flatSchema(List<Integer> ids, List<Action> actions) {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        root.putArray("required").add("items");
        ObjectNode arr = root.putObject("properties").putObject("items");
        arr.put("type", "array").put("minItems", ids.size()).put("maxItems", ids.size());
        ObjectNode item = arr.putObject("items");
        item.put("type", "object");
        item.putArray("required").add("id").add("action").add("newRow").add("flags");
        ObjectNode ip = item.putObject("properties");
        ObjectNode id = ip.putObject("id");
        id.put("type", "integer");
        ArrayNode idEnum = id.putArray("enum");
        ids.forEach(idEnum::add);
        enumOf(ip.putObject("action"), "string", names(actions.toArray(Enum[]::new)));
        ip.putObject("newRow").put("type", "boolean");
        ObjectNode flags = ip.putObject("flags");
        flags.put("type", "array");
        enumOf(flags.putObject("items"), "string", names(Flag.values()));
        return JSON.writeValueAsString(root);
    }

    private static void enumOf(ObjectNode node, String type, List<String> values) {
        node.put("type", type);
        ArrayNode e = node.putArray("enum");
        values.forEach(e::add);
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> out = new ArrayList<>();
        for (Enum<?> v : values) {
            out.add(v.name());
        }
        return out;
    }

    static String mmss(long ms) {
        return ms / 60_000 + ":" + String.format(Locale.ROOT, "%02d", ms / 1000 % 60);
    }

    private static String head(String text, int codePoints) {
        int n = text.codePointCount(0, text.length());
        return n <= codePoints ? text : text.substring(0, text.offsetByCodePoints(0, codePoints));
    }
}

package koja.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import koja.core.StageStore;
import koja.core.StageStore.DiarResult;
import koja.core.StageStore.Turn;
import koja.runtime.AppPaths;
import koja.runtime.ModelStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SherpaEngineTest {
    @TempDir
    Path tmp;

    @Test
    void loadsNativeSherpaOnnxLibrary() {
        String version = SherpaNative.load();
        assertFalse(version.isBlank());
    }

    @Test
    void modelSpecsMatchExpectedPathsAndChecksums() {
        assertEquals("sherpa/silero_vad.onnx", SherpaModels.SILERO_VAD.relativePath());
        assertEquals(643_854L, SherpaModels.SILERO_VAD.expectedSize());
        assertEquals("sherpa/pyannote-segmentation-3-0/model.onnx", SherpaModels.PYANNOTE_SEGMENTATION.relativePath());
        assertEquals(5_992_913L, SherpaModels.PYANNOTE_SEGMENTATION.expectedSize());
        assertEquals("sherpa/campplus_zh_en.onnx", SherpaModels.CAMPPLUS_EMBEDDING.relativePath());
        assertEquals(28_281_165L, SherpaModels.CAMPPLUS_EMBEDDING.expectedSize());
    }

    @Test
    void modelStoreVerifiesInstalledSherpaModels() throws Exception {
        Path vadModel = findLocalVadModel();
        Path segModel = findLocalSegModel();
        Path embModel = findLocalEmbModel();
        Assumptions.assumeTrue(vadModel != null && segModel != null && embModel != null,
                "로컬 sherpa 모델이 없어 ModelStore 체크섬 검증 테스트를 건너뜀");

        Path storeDir = tmp.resolve("모델 저장소");
        try (ModelStore store = new ModelStore(storeDir)) {
            copyToSpec(vadModel, store.pathOf(SherpaModels.SILERO_VAD));
            copyToSpec(segModel, store.pathOf(SherpaModels.PYANNOTE_SEGMENTATION));
            copyToSpec(embModel, store.pathOf(SherpaModels.CAMPPLUS_EMBEDDING));

            List<Long> vadProg = new ArrayList<>();
            assertEquals(store.pathOf(SherpaModels.SILERO_VAD),
                    SherpaModels.ensureVad(store, (done, total) -> vadProg.add(done), null));
            assertEquals(SherpaModels.SILERO_VAD.expectedSize(), vadProg.getLast());

            List<Long> diarProg = new ArrayList<>();
            SherpaModels.DiarModels diarModels = SherpaModels.ensureDiar(store, (done, total) -> diarProg.add(done), null);
            assertEquals(store.pathOf(SherpaModels.PYANNOTE_SEGMENTATION), diarModels.segmentation());
            assertEquals(store.pathOf(SherpaModels.CAMPPLUS_EMBEDDING), diarModels.embedding());
            assertEquals(
                    SherpaModels.PYANNOTE_SEGMENTATION.expectedSize() + SherpaModels.CAMPPLUS_EMBEDDING.expectedSize(),
                    diarProg.getLast());
        }
    }

    private static void copyToSpec(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst);
    }

    @Test
    void splitAtLowEnergySplitsLongSegmentAtQuietValley() {
        int sr = Vad.SAMPLE_RATE;
        float[] samples = new float[sr * 25];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.5f;
        }
        // 12초 지점에 50ms 무음 골짜기를 둔다.
        for (int i = sr * 12; i < sr * 12 + 800; i++) {
            samples[i] = 0.0f;
        }
        List<int[]> out = new ArrayList<>();
        Vad.splitAtLowEnergy(samples, 0, samples.length, sr * 10, out);

        assertTrue(out.size() >= 3);
        int prevEnd = 0;
        for (int[] range : out) {
            assertEquals(prevEnd, range[0]);
            assertTrue(range[1] > range[0]);
            assertTrue(range[1] - range[0] <= sr * 10);
            prevEnd = range[1];
        }
        assertEquals(samples.length, prevEnd);
    }

    @Test
    void rejectsMissingOrCorruptedModelFilesWithoutCrashingJvm() throws Exception {
        Path missing = tmp.resolve("missing.onnx");
        Path empty = tmp.resolve("empty.onnx");
        Path corrupted = tmp.resolve("corrupted.onnx");
        Files.write(empty, new byte[0]);
        Files.writeString(corrupted, "not a valid onnx model");

        assertThrows(IOException.class, () -> Vad.open(missing));
        assertThrows(IOException.class, () -> Vad.open(empty));
        assertThrows(IOException.class, () -> Vad.open(corrupted));
        assertThrows(IOException.class, () -> SherpaDiarizer.open(missing, empty));
        assertThrows(IOException.class, () -> SherpaDiarizer.open(empty, missing));
        assertThrows(IOException.class, () -> SherpaDiarizer.open(corrupted, corrupted));
    }

    @Test
    void diarizerConfigAndTurnConversionAreValidForStageStore() throws Exception {
        assertEquals("sherpa-campplus@0.9",
                SherpaDiarizer.engineName(SherpaDiarizer.Config.withThreshold(0.9f)));
        assertEquals("sherpa-campplus@0.85",
                SherpaDiarizer.engineName(SherpaDiarizer.Config.withThreshold(0.85f)));
        assertEquals("sherpa-campplus#k2",
                SherpaDiarizer.engineName(SherpaDiarizer.Config.withNumClusters(2)));

        assertThrows(IllegalArgumentException.class, () -> SherpaDiarizer.Config.withThreshold(0.0f));
        assertThrows(IllegalArgumentException.class, () -> SherpaDiarizer.Config.withNumClusters(0));
        assertThrows(IllegalArgumentException.class, () -> new Vad.Config(0.0f, 0.3f, 0.25f, 20.0f, 512, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Vad.Config(0.5f, 0.3f, 0.25f, 20.0f, 200, 0, 1));

        OfflineSpeakerDiarizationSegment[] raw = new OfflineSpeakerDiarizationSegment[] {
                new OfflineSpeakerDiarizationSegment(2.5f, 4.0f, 1, 0.9f),
                new OfflineSpeakerDiarizationSegment(-0.01f, 1.25f, 0, 0.95f),
                new OfflineSpeakerDiarizationSegment(5.0f, 4.9f, 2, 0.8f),
        };
        List<Turn> turns = SherpaDiarizer.toTurns(raw);
        assertEquals(List.of(
                new Turn(0L, 1250L, "S0"),
                new Turn(2500L, 4000L, "S1"),
                new Turn(5000L, 5000L, "S2")), turns);

        Path diarJson = tmp.resolve("diar.json");
        DiarResult result = new DiarResult(StageStore.CURRENT_VERSION, "sherpa-campplus@0.9", turns);
        StageStore.saveDiar(result, diarJson);
        assertEquals(result, StageStore.loadDiar(diarJson));
    }

    @Test
    void vadHandlesSilenceEmptyShortAndSyntheticAudio() throws Exception {
        Path model = findLocalVadModel();
        Assumptions.assumeTrue(model != null, "로컬 silero_vad.onnx 모델이 없어 실모델 VAD 테스트를 건너뜀");

        try (Vad vad = Vad.open(model)) {
            // 빈 오디오
            List<Double> emptyProg = new ArrayList<>();
            assertEquals(List.of(), vad.detect(new float[0], emptyProg::add));
            assertEquals(1.0, emptyProg.getLast());

            // 아주 짧은 오디오(10ms = 160샘플, windowSize 512 미만)
            assertEquals(List.of(), vad.detect(new float[160], null));

            // 3초 무음
            Path silenceWav = tmp.resolve("한글 폴더").resolve("무음 3초.wav");
            WavAudio.writeMonoPcm16(silenceWav, new float[Vad.SAMPLE_RATE * 3], Vad.SAMPLE_RATE);
            assertEquals(List.of(), vad.detect(silenceWav, null));

            // 합성 오디오(무음 + 2초 유성음 근사 파형 + 무음) — 오류 없이 유효한 구간 계약(0 ≤ startMs ≤ endMs) 유지
            float[] synth = syntheticVoiceLike(Vad.SAMPLE_RATE * 4, Vad.SAMPLE_RATE, Vad.SAMPLE_RATE * 3, 180.0);
            Path synthWav = tmp.resolve("한글 폴더").resolve("합성 오디오.wav");
            WavAudio.writeMonoPcm16(synthWav, synth, Vad.SAMPLE_RATE);
            List<Double> prog = new ArrayList<>();
            List<Vad.Segment> segments = vad.detect(synthWav, prog::add);
            assertFalse(prog.isEmpty());
            assertEquals(1.0, prog.getLast());
            long prevEnd = 0;
            for (Vad.Segment seg : segments) {
                assertTrue(seg.startMs() >= prevEnd);
                assertTrue(seg.endMs() > seg.startMs());
                assertTrue(seg.endMs() <= 4_000L);
                prevEnd = seg.endMs();
            }

            // 취소(interrupt): 호출 전 및 1초 입력의 진행률 콜백 안에서 들어온 취소 모두 감지
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedException.class, () -> vad.detect(synth, null));
            } finally {
                Thread.interrupted();
            }
            float[] oneSec = new float[Vad.SAMPLE_RATE];
            try {
                assertThrows(InterruptedException.class, () -> vad.detect(oneSec, f -> Thread.currentThread().interrupt()));
            } finally {
                Thread.interrupted();
            }

            // 닫기 멱등성 및 닫힌 뒤 호출 거부
            vad.close();
            vad.close();
            assertThrows(IllegalStateException.class, () -> vad.detect(new float[512], null));
        }
    }

    @Test
    void diarizerHandlesSilenceEmptyShortAndSyntheticAudio() throws Exception {
        Path segModel = findLocalSegModel();
        Path embModel = findLocalEmbModel();
        Assumptions.assumeTrue(segModel != null && embModel != null,
                "로컬 pyannote·CAM++ 모델이 없어 실모델 화자 구분 테스트를 건너뜀");

        try (SherpaDiarizer diarizer = SherpaDiarizer.open(segModel, embModel)) {
            // 빈 오디오
            DiarResult empty = diarizer.diarize(new float[0], null);
            assertEquals(List.of(), empty.turns());

            // 아주 짧은 오디오(20ms, 300ms)
            assertEquals(List.of(), diarizer.diarize(new float[320], null).turns());
            DiarResult shortRes = diarizer.diarize(new float[Vad.SAMPLE_RATE * 3 / 10], null);
            Path shortJson = tmp.resolve("short-diar.json");
            StageStore.saveDiar(shortRes, shortJson);
            assertEquals(shortRes, StageStore.loadDiar(shortJson));

            // 3초 무음
            Path silenceWav = tmp.resolve("화자 테스트").resolve("무음.wav");
            WavAudio.writeMonoPcm16(silenceWav, new float[SherpaDiarizer.SAMPLE_RATE * 3], SherpaDiarizer.SAMPLE_RATE);
            DiarResult silenceRes = diarizer.diarize(silenceWav, null);
            assertEquals(List.of(), silenceRes.turns());

            // 4초 합성 유성음 근사 파형 → diar.json 스키마(D-048) 저장·로드 통과
            float[] synth = syntheticVoiceLike(
                    SherpaDiarizer.SAMPLE_RATE * 4, SherpaDiarizer.SAMPLE_RATE / 2, SherpaDiarizer.SAMPLE_RATE * 3, 150.0);
            Path synthWav = tmp.resolve("화자 테스트").resolve("합성.wav");
            WavAudio.writeMonoPcm16(synthWav, synth, SherpaDiarizer.SAMPLE_RATE);
            List<Double> prog = new ArrayList<>();
            DiarResult synthRes = diarizer.diarize(synthWav, prog::add);
            assertFalse(prog.isEmpty());
            assertEquals(1.0, prog.getLast());
            Path diarJson = tmp.resolve("synth-diar.json");
            StageStore.saveDiar(synthRes, diarJson);
            assertEquals(synthRes, StageStore.loadDiar(diarJson));

            // 진행률 콜백 중 close()가 불려도 네이티브 호출이 끝난 뒤 안전하게 해제된다.
            diarizer.diarize(synthWav, f -> diarizer.close());
            // 닫기 멱등성 및 닫힌 뒤 호출 거부
            diarizer.close();
            assertThrows(IllegalStateException.class, () -> diarizer.diarize(new float[1600], null));
        }
    }

    /** 기본 주파수 + 배음 + 음절 크기 변조로 간단한 합성 음성 근사 파형을 만든다. */
    private static float[] syntheticVoiceLike(int totalSamples, int start, int end, double f0) {
        float[] samples = new float[totalSamples];
        for (int i = start; i < end && i < totalSamples; i++) {
            double t = (double) i / Vad.SAMPLE_RATE;
            double env = 0.5 * (1.0 + Math.sin(2.0 * Math.PI * 4.0 * t));
            double sig = 0.45 * Math.sin(2.0 * Math.PI * f0 * t)
                    + 0.25 * Math.sin(2.0 * Math.PI * 2.0 * f0 * t)
                    + 0.15 * Math.sin(2.0 * Math.PI * 3.0 * f0 * t)
                    + 0.10 * Math.sin(2.0 * Math.PI * 5.0 * f0 * t);
            samples[i] = (float) (sig * env * 0.7);
        }
        return samples;
    }

    private static Path findLocalVadModel() {
        return firstExisting(
                AppPaths.modelsDir().resolve(SherpaModels.SILERO_VAD.relativePath()),
                envModel("silero_vad.onnx"));
    }

    private static Path findLocalSegModel() {
        return firstExisting(
                AppPaths.modelsDir().resolve(SherpaModels.PYANNOTE_SEGMENTATION.relativePath()),
                envModel("diar/sherpa-onnx-pyannote-segmentation-3-0/model.onnx"));
    }

    private static Path findLocalEmbModel() {
        return firstExisting(
                AppPaths.modelsDir().resolve(SherpaModels.CAMPPLUS_EMBEDDING.relativePath()),
                envModel("diar/campplus_zh_en.onnx"));
    }

    private static Path envModel(String relative) {
        String dir = System.getenv("KOJA_TEST_MODELS_DIR");
        return dir == null || dir.isBlank() ? null : Path.of(dir).resolve(relative);
    }

    private static Path firstExisting(Path... candidates) {
        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }
}

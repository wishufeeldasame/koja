package koja.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import koja.core.Project;
import koja.core.ProjectFiles;
import koja.core.ProjectStore;
import koja.engine.LocalEngines;
import koja.engine.Pipeline;
import koja.runtime.AppPaths;
import koja.runtime.ModelStore;
import koja.runtime.ModelStore.CancelToken;
import koja.runtime.ModelStore.ModelSpec;

/**
 * M2 처리 창: 빠진 모델을 받고(처음 한 번) STT → 화자 구분 → 행 만들기를 돌린다. FX 스레드에서 부른다.
 * 끝나면 onDone(저장된 프로젝트)을, 취소·실패면 안내한 뒤 onStopped를 부른다. 완료된 단계는 남아 다시 실행하면 이어서 한다.
 */
final class PipelineDialog {
    private PipelineDialog() {}

    static void run(Window owner, Path projectFile, Consumer<Project> onDone, Runnable onStopped) {
        // 폴더의 stt.json·diar.json은 project.json의 것이다. 같은 폴더의 다른 json(백업 등)으로 돌리면 그 파일들을 덮는다.
        if (!ProjectFiles.PROJECT.equals(projectFile.getFileName().toString())) {
            alert(owner, Alert.AlertType.ERROR, "처리할 수 없습니다", ProjectFiles.PROJECT
                    + "만 처리할 수 있습니다. 가져오기로 만든 프로젝트 폴더의 " + ProjectFiles.PROJECT + "를 여세요.");
            return;
        }
        boolean stt;
        boolean diar;
        Project project;
        try {
            project = ProjectStore.load(projectFile);
            stt = Pipeline.pending(projectFile, project, Pipeline.STT);
            diar = Pipeline.pending(projectFile, project, Pipeline.DIAR);
        } catch (IOException e) {
            alert(owner, Alert.AlertType.ERROR, "처리할 수 없습니다", e.getMessage());
            return;
        }
        // audio.wav가 이 프로젝트의 가져오기 결과인지 본다. 다른 영상 가져오기가 남긴 폴더에 만든 더미 등은 거부한다.
        Project.Stage imported = project.stages() == null ? null : project.stages().get("import");
        if (imported == null || !imported.done()) {
            alert(owner, Alert.AlertType.ERROR, "처리할 수 없습니다", "가져오기로 만든 프로젝트가 아닙니다. 파일 → 가져오기로 영상을 가져오세요.");
            return;
        }
        // 저장한 검수 내용도 행을 새로 만들면 사라진다. 저장 안 한 편집 확인과 별개로 묻는다.
        if (!project.rows().isEmpty() && Pipeline.rebuildsRows(projectFile, project) && !confirm(owner, "행 다시 만들기",
                "저장된 행 " + project.rows().size() + "개(검수한 내용 포함)를 STT 구간으로 새로 만듭니다. 계속할까요?")) {
            return;
        }
        // 모델 1.7GB를 받은 뒤에야 오디오가 없다고 실패하지 않게 먼저 본다(더미 프로젝트 등).
        if ((stt || diar) && !Files.isRegularFile(projectFile.toAbsolutePath().resolveSibling(ProjectFiles.AUDIO))) {
            alert(owner, Alert.AlertType.ERROR, "처리할 수 없습니다", ProjectFiles.AUDIO
                    + "가 프로젝트 폴더에 없습니다. 파일 → 가져오기로 영상을 가져오세요.");
            return;
        }
        ModelStore store = new ModelStore(AppPaths.modelsDir());
        List<ModelSpec> missing = LocalEngines.missing(store, stt, diar);
        List<ModelSpec> ensure = LocalEngines.toEnsure(store, stt, diar);
        if (!missing.isEmpty() && !confirmDownload(owner, store, missing)) {
            store.close();
            return;
        }
        CancelToken token = new CancelToken();
        Label step = new Label("준비 중");
        ProgressBar bar = new ProgressBar(-1);
        bar.setMaxWidth(Double.MAX_VALUE);
        Button cancel = new Button("취소");

        Task<Project> task = new Task<>() {
            @Override
            protected Project call() throws Exception {
                long total = ensure.stream().mapToLong(ModelSpec::expectedSize).sum();
                long before = 0;
                for (int i = 0; i < ensure.size(); i++) {
                    ModelSpec spec = ensure.get(i);
                    updateMessage("모델 확인·받는 중 (" + (i + 1) + "/" + ensure.size() + "): " + spec.relativePath());
                    long offset = before;
                    store.ensureDownloaded(spec, (done, unused) -> updateProgress(offset + done, total), token);
                    before += spec.expectedSize();
                }
                return Pipeline.run(projectFile, LocalEngines.transcriber(store), LocalEngines.diarizer(store),
                        (stage, f) -> {
                            updateMessage(label(stage));
                            updateProgress(f < 0 ? -1 : f, 1);
                        });
            }
        };
        step.textProperty().bind(task.messageProperty());
        bar.progressProperty().bind(task.progressProperty());

        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("전사·화자 구분 - " + projectFile.toAbsolutePath().getParent().getFileName());
        VBox box = new VBox(10, step, bar, cancel);
        box.setPadding(new Insets(16));
        dialog.setScene(new Scene(box, 480, 140));

        Thread worker = new Thread(task, "koja-pipeline");
        worker.setDaemon(true);
        // 작업 스레드가 엔진(서버 프로세스)을 내리고 끝나야 창이 닫힌다. 그 전에 다시 실행하면 GPU를 둘이 쓰게 된다.
        Runnable stop = () -> {
            cancel.setDisable(true);
            step.textProperty().unbind();
            step.setText("취소하는 중 (엔진을 내리고 있습니다)");
            token.cancel();
            worker.interrupt();
        };
        cancel.setOnAction(e -> stop.run());
        dialog.setOnCloseRequest(e -> {
            e.consume();
            stop.run();
        });

        task.setOnSucceeded(e -> {
            dialog.close();
            store.close();
            onDone.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            dialog.close();
            store.close();
            Throwable t = task.getException();
            boolean cancelled = t instanceof InterruptedException || t instanceof CancellationException;
            alert(owner, cancelled ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                    cancelled ? "취소했습니다" : "처리에 실패했습니다",
                    (cancelled ? "" : (t.getMessage() == null ? t.toString() : t.getMessage()) + "\n\n")
                            + "끝난 단계와 받은 모델은 남아 있습니다. 다시 실행하면 이어서 합니다(처리 → 전사·화자 구분 실행)."
                            + (cancelled ? "" : "\n오류가 " + ProjectFiles.STT + "·" + ProjectFiles.DIAR
                                    + "를 가리키면 그 파일이 손상된 것입니다. 지우고 다시 실행하면 그 단계부터 다시 합니다."));
            // 끝난 단계가 디스크에 저장됐다. 화면이 옛 프로젝트를 들고 있으면 저장할 때 그 완료 표시를 덮는다.
            onStopped.run();
        });

        dialog.show();
        worker.start();
    }

    private static void alert(Window owner, Alert.AlertType type, String header, String text) {
        Alert alert = new Alert(type, text);
        alert.initOwner(owner);
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    private static boolean confirm(Window owner, String header, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setHeaderText(header);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private static boolean confirmDownload(Window owner, ModelStore store, List<ModelSpec> missing) {
        long bytes = missing.stream().mapToLong(ModelSpec::expectedSize).sum();
        return confirm(owner, "모델 다운로드", String.format(Locale.ROOT,
                "처음 한 번 로컬 모델 %d개(약 %.1f GB)를 받습니다.%n저장 위치: %s%n중간에 취소해도 받은 만큼은 남아 다음에 이어 받습니다.",
                missing.size(), bytes / 1e9, store.modelsDir()));
    }

    private static String label(String stage) {
        return switch (stage) {
            case Pipeline.STT -> "음성 인식(STT)";
            case Pipeline.DIAR -> "화자 구분";
            default -> stage;
        };
    }
}

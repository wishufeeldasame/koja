package koja.ui;

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
import koja.engine.LocalEngines;
import koja.engine.Pipeline;
import koja.runtime.AppPaths;
import koja.runtime.ModelStore;
import koja.runtime.ModelStore.CancelToken;
import koja.runtime.ModelStore.ModelSpec;

/**
 * M2 처리 창: 빠진 모델을 받고(처음 한 번) STT → 화자 구분 → 행 만들기를 돌린다. FX 스레드에서 부른다.
 * 끝나면 onDone(저장된 프로젝트)을 부른다. 취소·실패는 이 창이 안내한다. 완료된 단계는 남아 다시 실행하면 이어서 한다.
 */
final class PipelineDialog {
    private PipelineDialog() {}

    static void run(Window owner, Path projectFile, Consumer<Project> onDone) {
        ModelStore store = new ModelStore(AppPaths.modelsDir());
        List<ModelSpec> missing = LocalEngines.missing(store);
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
                long total = missing.stream().mapToLong(ModelSpec::expectedSize).sum();
                long before = 0;
                for (int i = 0; i < missing.size(); i++) {
                    ModelSpec spec = missing.get(i);
                    updateMessage("모델 받는 중 (" + (i + 1) + "/" + missing.size() + "): " + spec.relativePath());
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
            Alert alert = new Alert(cancelled ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                    (cancelled ? "" : (t.getMessage() == null ? t.toString() : t.getMessage()) + "\n\n")
                            + "끝난 단계와 받은 모델은 남아 있습니다. 다시 실행하면 이어서 합니다(처리 → 전사·화자 구분 실행).");
            alert.initOwner(owner);
            alert.setHeaderText(cancelled ? "취소했습니다" : "처리에 실패했습니다");
            alert.showAndWait();
        });

        dialog.show();
        worker.start();
    }

    private static boolean confirmDownload(Window owner, ModelStore store, List<ModelSpec> missing) {
        long bytes = missing.stream().mapToLong(ModelSpec::expectedSize).sum();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                String.format(Locale.ROOT, "처음 한 번 로컬 모델 %d개(약 %.1f GB)를 받습니다.%n저장 위치: %s%n"
                        + "중간에 취소해도 받은 만큼은 남아 다음에 이어 받습니다.",
                        missing.size(), bytes / 1e9, store.modelsDir()),
                ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setHeaderText("모델 다운로드");
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private static String label(String stage) {
        return switch (stage) {
            case Pipeline.STT -> "음성 인식(STT)";
            case Pipeline.DIAR -> "화자 구분";
            default -> stage;
        };
    }
}

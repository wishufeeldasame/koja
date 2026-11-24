package koja.ui;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import koja.media.Importer;
import koja.runtime.Ffmpeg;

/** 변환 진행 창. FX 스레드에서 부른다. 끝나면 onDone(성공), onError(실패) 중 하나를 부르고, 취소하면 아무것도 안 부른다. */
final class ImportDialog {
    private ImportDialog() {}

    static void run(Window owner, Path source, Path projectDir,
            Consumer<Importer.Result> onDone, Consumer<Throwable> onError) {
        Importer importer = new Importer(Ffmpeg.executable(), Ffmpeg.h264Encoders());
        Label step = new Label("준비 중");
        ProgressBar bar = new ProgressBar(-1);
        bar.setMaxWidth(Double.MAX_VALUE);
        Button cancel = new Button("취소");

        Task<Importer.Result> task = new Task<>() {
            @Override
            protected Importer.Result call() throws Exception {
                return importer.run(source, projectDir, (name, fraction) -> {
                    updateMessage(name);
                    updateProgress(fraction < 0 ? -1 : fraction, 1);
                });
            }
        };
        step.textProperty().bind(task.messageProperty());
        bar.progressProperty().bind(task.progressProperty());

        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("가져오기 - " + source.getFileName());
        VBox box = new VBox(10, new Label(source.toString()), step, bar, cancel);
        box.setPadding(new Insets(16));
        dialog.setScene(new Scene(box, 480, 160));

        // 작업 스레드가 ffmpeg 종료와 임시 파일 정리를 끝내면 CancellationException으로 실패하고, 그때 창이 닫힌다.
        Runnable stop = () -> {
            cancel.setDisable(true);
            step.textProperty().unbind();
            step.setText("취소하는 중");
            importer.cancel();
        };
        cancel.setOnAction(e -> stop.run());
        dialog.setOnCloseRequest(e -> {
            e.consume();
            stop.run();
        });

        task.setOnSucceeded(e -> {
            dialog.close();
            onDone.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            dialog.close();
            if (!(task.getException() instanceof CancellationException)) {
                onError.accept(task.getException());
            }
        });

        dialog.show();
        Thread worker = new Thread(task, "koja-import");
        worker.setDaemon(true);
        worker.start();
    }
}

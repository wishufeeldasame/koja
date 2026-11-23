package koja.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import koja.core.Project;
import koja.core.ProjectFiles;
import koja.core.ProjectStore;

public final class KojaApp extends Application {
    private final ReviewView review = new ReviewView();
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        MenuItem open = new MenuItem("열기…");
        open.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        open.setOnAction(e -> openChosen());
        MenuItem dummy = new MenuItem("더미 프로젝트 만들기…");
        dummy.setOnAction(e -> createDummy());
        MenuItem save = new MenuItem("저장");
        save.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        save.setOnAction(e -> save());
        MenuBar menus = new MenuBar(new Menu("파일", null, open, dummy, save));

        stage.setTitle("koja");
        stage.setScene(new Scene(new BorderPane(review, menus, null, null, null), 1280, 720));
        stage.setOnCloseRequest(e -> {
            if (!confirmDiscard()) {
                e.consume();
            }
        });
        stage.show();

        // 개발용: ./gradlew run --args="'경로/project.json'" (Gradle이 공백에서 인자를 자르므로 작은따옴표로 감싼다)
        List<String> args = getParameters().getRaw();
        if (!args.isEmpty()) {
            open(Path.of(args.getFirst()));
        }
    }

    @Override
    public void stop() {
        review.dispose();
    }

    private void openChosen() {
        if (!confirmDiscard()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("project.json 열기");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("project.json", "*.json"));
        File chosen = chooser.showOpenDialog(stage);
        if (chosen != null) {
            open(chosen.toPath());
        }
    }

    private void open(Path file) {
        try {
            show(ProjectStore.load(file), file);
        } catch (IOException | RuntimeException e) {
            error("열 수 없습니다", e);
        }
    }

    private void createDummy() {
        if (!confirmDiscard()) {
            return;
        }
        FileChooser videoChooser = new FileChooser();
        videoChooser.setTitle("더미 프로젝트에 쓸 영상(MP4)");
        videoChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4", "*.mp4", "*.m4v"));
        File video = videoChooser.showOpenDialog(stage);
        if (video == null) {
            return;
        }
        FileChooser target = new FileChooser();
        target.setTitle("project.json 저장 위치");
        target.setInitialDirectory(video.getParentFile());
        target.setInitialFileName("project.json");
        File chosen = target.showSaveDialog(stage);
        if (chosen == null) {
            return;
        }
        // 열기 메뉴가 *.json만 보여 준다.
        Path file = chosen.getName().endsWith(".json") ? chosen.toPath()
                : chosen.toPath().resolveSibling(chosen.getName() + ".json");
        // 다른 영상의 프록시가 있는 폴더면 고른 영상 대신 그 프록시가 재생된다(D-028).
        if (Files.exists(file.toAbsolutePath().getParent().resolve(ProjectFiles.PROXY))) {
            error("저장할 수 없습니다", new IOException(ProjectFiles.PROXY + "가 이미 있는 폴더입니다. 빈 폴더를 고르세요."));
            return;
        }
        Project project = DummyProject.create(video.toPath());
        try {
            ProjectStore.save(project, file);
        } catch (IOException e) {
            error("저장할 수 없습니다", e);
            return;
        }
        show(project, file);
    }

    private void save() {
        review.commitActiveEdit();
        Project edited = review.edited();
        if (edited == null) {
            return;
        }
        try {
            ProjectStore.save(edited, review.file());
            review.markSaved(edited);
        } catch (IOException e) {
            error("저장할 수 없습니다", e);
        }
    }

    private void show(Project project, Path file) {
        review.show(project, file);
        stage.setTitle("koja - " + file.toAbsolutePath());
    }

    /** 저장 안 한 편집이 있으면 버려도 되는지 묻는다. */
    private boolean confirmDiscard() {
        review.commitActiveEdit();
        if (!review.dirty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "저장하지 않은 변경을 버릴까요?",
                ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void error(String title, Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
        alert.initOwner(stage);
        alert.setHeaderText(title);
        alert.showAndWait();
    }
}

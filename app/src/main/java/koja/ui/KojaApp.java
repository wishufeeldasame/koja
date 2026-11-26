package koja.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
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
        MenuItem importItem = new MenuItem("가져오기…");
        importItem.setAccelerator(KeyCombination.keyCombination("Shortcut+I"));
        importItem.setOnAction(e -> importChosen());
        MenuItem dummy = new MenuItem("더미 프로젝트 만들기…");
        dummy.setOnAction(e -> createDummy());
        MenuItem save = new MenuItem("저장");
        save.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        save.setOnAction(e -> save());
        MenuItem process = new MenuItem("전사·화자 구분 실행");
        process.setAccelerator(KeyCombination.keyCombination("Shortcut+R"));
        process.setOnAction(e -> processOpen());
        MenuBar menus = new MenuBar(new Menu("파일", null, importItem, open, dummy, save), new Menu("처리", null, process));

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

    /** 영상 선택 → 프로젝트 폴더 선택 → 변환 → project.json → 검수 화면. */
    private void importChosen() {
        if (!confirmDiscard()) {
            return;
        }
        FileChooser videoChooser = new FileChooser();
        videoChooser.setTitle("가져올 영상");
        videoChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("영상", "*.mp4", "*.m4v", "*.mov", "*.mkv", "*.avi", "*.mts", "*.m2ts",
                        "*.ts", "*.webm", "*.wmv", "*.MP4", "*.MOV", "*.MTS"),
                new FileChooser.ExtensionFilter("모든 파일", "*.*"));
        File video = videoChooser.showOpenDialog(stage);
        if (video == null) {
            return;
        }
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("프로젝트 폴더(빈 폴더)");
        dirChooser.setInitialDirectory(video.getParentFile());
        File chosenDir = dirChooser.showDialog(stage);
        if (chosenDir == null) {
            return;
        }
        Path dir = chosenDir.toPath();
        Path file = dir.resolve("project.json");
        // 다른 영상의 프로젝트나 프록시를 덮어쓰지 않는다.
        if (Files.exists(file) || Files.exists(dir.resolve(ProjectFiles.PROXY))
                || Files.exists(dir.resolve(ProjectFiles.AUDIO))) {
            error("가져올 수 없습니다", new IOException("project.json, " + ProjectFiles.PROXY + ", " + ProjectFiles.AUDIO
                    + " 중 하나가 이미 있는 폴더입니다. 빈 폴더를 고르세요."));
            return;
        }
        Path source = video.toPath().toAbsolutePath();
        ImportDialog.run(stage, source, dir, result -> {
            String name = source.getFileName().toString();
            Project project = new Project(Project.CURRENT_VERSION,
                    new Project.Source(source.toString(), result.durationMs()),
                    new Project.Meta(name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name, "", List.of()),
                    null, List.of(), List.of(), List.of(), List.of(), null,
                    Map.of("import", new Project.Stage("ffmpeg", true)));
            try {
                ProjectStore.save(project, file);
            } catch (IOException e) {
                // 프로젝트 없이 남으면 같은 폴더로 다시 가져올 수 없다. 이번 가져오기가 만든 파일이다(위에서 없음을 확인).
                try {
                    Files.deleteIfExists(dir.resolve(ProjectFiles.AUDIO));
                    Files.deleteIfExists(dir.resolve(ProjectFiles.PROXY));
                } catch (IOException ignored) {
                    // 저장 실패를 알리는 것이 먼저다.
                }
                error("저장할 수 없습니다", e);
                return;
            }
            if (result.proxyEncoder() == null) {
                Alert alert = new Alert(Alert.AlertType.WARNING,
                        "검수 화면은 원본 영상을 재생합니다. 원본 형식에 따라 재생되지 않을 수 있습니다.\n\n" + result.proxyError());
                alert.initOwner(stage);
                alert.setHeaderText("프록시 영상을 만들지 못했습니다");
                alert.showAndWait();
            }
            show(project, file);
            process(file);
        }, e -> error("가져오기에 실패했습니다", e instanceof Exception ex ? ex : new IOException(e)));
    }

    /** 열린 프로젝트를 다시 처리한다. 끝난 단계는 건너뛰므로 취소·실패 뒤 이어 하기도 이것이다. */
    private void processOpen() {
        if (review.file() == null) {
            error("실행할 수 없습니다", new IOException("먼저 프로젝트를 가져오거나 여세요."));
            return;
        }
        // 파이프라인은 디스크의 project.json을 읽어 고친다. 저장 안 한 편집은 덮인다.
        if (!confirmDiscard()) {
            return;
        }
        process(review.file());
    }

    private void process(Path file) {
        PipelineDialog.run(stage, file, project -> show(project, file));
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

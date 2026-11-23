package koja.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import koja.core.Project;
import koja.core.Project.Row;
import koja.core.ProjectFiles;
import koja.core.Timecode;

/** 검수 화면: 왼쪽 영상, 오른쪽 행 표. 행을 누르면 그 시간으로 영상이 이동한다. */
final class ReviewView extends SplitPane {
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final MediaView mediaView = new MediaView();
    private final Label videoMessage = new Label("파일 > 열기로 project.json을 여세요");
    private final Label timeLabel = new Label(Timecode.format(0L));
    private final Button playButton = new Button("재생");

    private Project project;
    private Path file;
    private Map<String, String> speakerNames = Map.of();
    private boolean dirty;
    private EditCell activeCell;

    ReviewView() {
        StackPane screen = new StackPane(mediaView, videoMessage);
        screen.setMinSize(0, 0);
        screen.setStyle("-fx-background-color: black;");
        videoMessage.setStyle("-fx-text-fill: white;");
        videoMessage.setWrapText(true);
        mediaView.setPreserveRatio(true);
        mediaView.fitWidthProperty().bind(screen.widthProperty());
        mediaView.fitHeightProperty().bind(screen.heightProperty());

        playButton.setDisable(true);
        playButton.setOnAction(e -> togglePlay());
        HBox controls = new HBox(8, playButton, timeLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(6));
        BorderPane player = new BorderPane(screen, null, null, controls, null);

        table.setEditable(true);
        table.setPlaceholder(new Label("행이 없습니다"));
        table.getColumns().add(column("시간", 70, r -> Timecode.format(r.startMs())));
        table.getColumns().add(column("종류", 110, r -> r.kind().name()));
        table.getColumns().add(column("화자", 110, r -> r.speakerId() == null ? ""
                : speakerNames.getOrDefault(r.speakerId(), r.speakerId())));
        table.getColumns().add(textColumn("한국어", 300, Row::ko, (r, v) -> new Row(
                r.id(), r.kind(), r.startMs(), r.speakerId(), v, r.ja(), r.segIds(), r.flags())));
        table.getColumns().add(textColumn("일본어", 300, Row::ja, (r, v) -> new Row(
                r.id(), r.kind(), r.startMs(), r.speakerId(), r.ko(), v, r.segIds(), r.flags())));
        table.getColumns().add(column("플래그", 180, r -> r.flags().stream()
                .map(Enum::name).collect(Collectors.joining(", "))));
        // 선택 변경이 아니라 클릭에 반응한다. 셀 편집 후 행이 교체될 때 영상이 다시 튀지 않게 하려고.
        table.setRowFactory(t -> {
            TableRow<Row> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty()) {
                    seek(row.getItem().startMs());
                }
            });
            return row;
        });

        getItems().addAll(player, table);
        setDividerPositions(0.4);
    }

    /** 실패할 수 있는 준비를 먼저 끝낸다. 도중에 예외가 나면 이전 프로젝트와 저장 대상이 그대로 남는다. */
    void show(Project project, Path file) {
        Map<String, String> names = new HashMap<>();
        project.speakers().forEach(s -> names.putIfAbsent(s.id(), s.name()));
        Path video = ProjectFiles.playbackVideo(file, project);
        this.project = project;
        this.file = file;
        speakerNames = names;
        rows.setAll(project.rows());
        dirty = false;
        loadVideo(video);
    }

    /** 편집 중인 셀이 있으면 그 입력을 반영한다. 저장·버림 확인 전에 부른다. */
    void commitActiveEdit() {
        if (activeCell != null && activeCell.isEditing()) {
            activeCell.commitEdit(activeCell.field.getText());
        }
    }

    /** 편집한 행을 반영한 프로젝트. 열린 프로젝트가 없으면 null. */
    Project edited() {
        if (project == null) {
            return null;
        }
        Project p = project;
        return new Project(p.version(), p.source(), p.meta(), p.videoType(), p.speakers(), p.sttSegments(),
                List.copyOf(rows), p.glossary(), p.stages());
    }

    Path file() {
        return file;
    }

    boolean dirty() {
        return dirty;
    }

    void markSaved(Project saved) {
        project = saved;
        dirty = false;
    }

    void dispose() {
        MediaPlayer old = mediaView.getMediaPlayer();
        if (old != null) {
            old.dispose();
            mediaView.setMediaPlayer(null);
        }
        playButton.setDisable(true);
    }

    private void loadVideo(Path video) {
        dispose();
        timeLabel.setText(Timecode.format(0L));
        if (!Files.isRegularFile(video)) {
            videoMessage.setText("영상 파일이 없습니다: " + video);
            return;
        }
        MediaPlayer player;
        try {
            player = new MediaPlayer(new Media(video.toUri().toString()));
        } catch (MediaException e) {
            videoMessage.setText("재생할 수 없는 영상입니다: " + video + "\n" + e.getMessage());
            return;
        }
        videoMessage.setText("");
        player.setOnReady(() -> playButton.setDisable(false));
        player.setOnError(() -> videoMessage.setText("영상 오류: " + player.getError().getMessage()));
        player.setOnPlaying(() -> playButton.setText("일시정지"));
        player.setOnPaused(() -> playButton.setText("재생"));
        player.setOnEndOfMedia(() -> {
            player.pause();
            playButton.setText("재생");
        });
        player.currentTimeProperty().addListener((obs, old, now) ->
                timeLabel.setText(Timecode.format((long) now.toMillis())));
        mediaView.setMediaPlayer(player);
    }

    private void togglePlay() {
        MediaPlayer player = mediaView.getMediaPlayer();
        if (player == null) {
            return;
        }
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
        } else {
            player.play();
        }
    }

    private void seek(Long ms) {
        MediaPlayer player = mediaView.getMediaPlayer();
        if (player != null && ms != null) {
            player.seek(Duration.millis(ms));
            // 첫 재생 전에는 seek가 currentTime을 갱신하지 않는다.
            timeLabel.setText(Timecode.format(ms));
        }
    }

    private static TableColumn<Row, String> column(String title, double width, Function<Row, String> value) {
        TableColumn<Row, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setEditable(false);
        // 정렬하면 rows 자체가 재정렬돼 저장 순서가 바뀐다.
        col.setSortable(false);
        col.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue())));
        return col;
    }

    private TableColumn<Row, String> textColumn(
            String title, double width, Function<Row, String> value, BiFunction<Row, String, Row> update) {
        TableColumn<Row, String> col = column(title, width, value);
        col.setEditable(true);
        col.setCellFactory(c -> new EditCell());
        col.setOnEditCommit(e -> {
            int i = e.getTablePosition().getRow();
            Row old = rows.get(i);
            if (!e.getNewValue().equals(value.apply(old))) {
                rows.set(i, update.apply(old, e.getNewValue()));
                dirty = true;
            }
        });
        return col;
    }

    /** TextFieldTableCell은 Enter 없이 저장하면 입력이 빠진다. 편집 중인 셀을 기억해 commitActiveEdit에서 확정한다. */
    private final class EditCell extends TableCell<Row, String> {
        private final TextField field = new TextField();

        EditCell() {
            field.setOnAction(e -> commitEdit(field.getText()));
            field.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                }
            });
            field.focusedProperty().addListener((obs, was, focused) -> {
                if (!focused && isEditing()) {
                    commitEdit(field.getText());
                }
            });
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (isEditing()) {
                activeCell = this;
                field.setText(getItem());
                setText(null);
                setGraphic(field);
                field.requestFocus();
                field.selectAll();
            }
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            showText();
        }

        @Override
        public void commitEdit(String value) {
            super.commitEdit(value);
            showText();
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (isEditing()) {
                field.setText(item);
            } else {
                showText();
            }
        }

        private void showText() {
            if (activeCell == this) {
                activeCell = null;
            }
            setGraphic(null);
            setText(isEmpty() ? null : getItem());
        }
    }
}

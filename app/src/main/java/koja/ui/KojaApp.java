package koja.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public final class KojaApp extends Application {
    @Override
    public void start(Stage stage) {
        stage.setTitle("koja");
        stage.setScene(new Scene(new StackPane(new Label("koja")), 960, 600));
        stage.show();
    }
}

module koja {
    requires java.net.http;
    requires javafx.controls;
    requires javafx.media;
    requires tools.jackson.databind;
    requires java.net.http;
    requires jdk.httpserver;

    exports koja.ui to javafx.graphics;
    // Jackson이 레코드를 리플렉션으로 만든다.
    opens koja.core to tools.jackson.databind;
}

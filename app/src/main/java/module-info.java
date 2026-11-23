module koja {
    requires javafx.controls;
    requires tools.jackson.databind;

    exports koja.ui to javafx.graphics;
    // Jackson이 레코드를 리플렉션으로 만든다.
    opens koja.core to tools.jackson.databind;
}

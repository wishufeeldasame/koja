package koja.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SecretStoreTest {
    @Test
    void rejectsUnsafeNames() {
        assertThrows(IllegalArgumentException.class, () -> SecretStore.checkName("../x"));
        assertThrows(IllegalArgumentException.class, () -> SecretStore.checkName("a b"));
        assertEquals("google-refresh-token", SecretStore.checkName("google-refresh-token"));
    }

    @Test
    void quotesForSecurityInteractive() {
        assertEquals("\"a\\\"b\\\\c\"", SecretStore.MacKeychain.quote("a\"b\\c"));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macKeychainRoundTrip() throws Exception {
        SecretStore store = new SecretStore.MacKeychain("koja-test");
        roundTrip(store);
        assertThrows(IllegalArgumentException.class, () -> store.put("t", "한글"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsDpapiRoundTrip(@TempDir Path dir) throws Exception {
        SecretStore store = new SecretStore.WindowsDpapi(dir.resolve("비밀 폴더"));
        roundTrip(store);
        store.put("t", "평문이 아니어야 함");
        String blob = Files.readString(dir.resolve("비밀 폴더").resolve("t.dpapi"));
        assertEquals(false, blob.contains("평문"));
    }

    private static void roundTrip(SecretStore store) throws Exception {
        String name = "test-" + System.nanoTime();
        try {
            assertEquals(Optional.empty(), store.get(name));
            store.put(name, "1//0a-b_c\"d\\e f");
            assertEquals(Optional.of("1//0a-b_c\"d\\e f"), store.get(name));
            store.put(name, "second");
            assertEquals(Optional.of("second"), store.get(name));
        } finally {
            store.delete(name);
        }
        assertEquals(Optional.empty(), store.get(name));
        store.delete(name);
    }
}

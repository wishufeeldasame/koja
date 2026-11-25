package koja.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 토큰·API 키를 OS 보관소에 둔다(절대 규칙 4, D-056). 맥은 Keychain(security CLI),
 * 윈도우는 DPAPI(PowerShell)로 암호화한 파일. 값은 프로세스 인자가 아니라 표준 입력으로 넘긴다.
 */
public interface SecretStore {
    Optional<String> get(String name) throws IOException;

    void put(String name, String value) throws IOException;

    void delete(String name) throws IOException;

    static SecretStore system() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return new MacKeychain("koja");
        }
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            Path base = local != null && !local.isBlank() ? Path.of(local)
                    : Path.of(System.getProperty("user.home"), "AppData", "Local");
            return new WindowsDpapi(base.resolve("koja").resolve("secrets"));
        }
        throw new UnsupportedOperationException("OS 보관소를 지원하지 않는 OS: " + os);
    }

    /** 이름은 파일명·키체인 계정명으로 쓰이므로 안전한 문자만 받는다. */
    static String checkName(String name) {
        if (!name.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("비밀값 이름 형식이 아니다: " + name);
        }
        return name;
    }

    /** 맥 Keychain. {@code security -i}에 명령을 표준 입력으로 넣어 값이 ps에 보이지 않게 한다. */
    record MacKeychain(String service) implements SecretStore {
        @Override
        public Optional<String> get(String name) throws IOException {
            Run r = run(List.of("security", "find-generic-password", "-s", service, "-a", checkName(name), "-w"), "");
            if (r.code == 44) { // errSecItemNotFound
                return Optional.empty();
            }
            return Optional.of(r.ok().stripTrailing());
        }

        @Override
        public void put(String name, String value) throws IOException {
            // shortcut: security -w는 ASCII 밖의 값을 16진수로 돌려준다. 토큰·API 키는 ASCII라 막아 둔다. 비ASCII가 필요하면 base64로 감싼다.
            if (value.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
                throw new IllegalArgumentException("출력 가능한 ASCII만 저장한다");
            }
            String cmd = "add-generic-password -U -s " + quote(service) + " -a " + quote(checkName(name))
                    + " -w " + quote(value) + "\n";
            run(List.of("security", "-i"), cmd).ok();
        }

        @Override
        public void delete(String name) throws IOException {
            Run r = run(List.of("security", "delete-generic-password", "-s", service, "-a", checkName(name)), "");
            if (r.code != 44) {
                r.ok();
            }
        }

        static String quote(String s) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
    }

    /** 윈도우 DPAPI(CurrentUser). 암호문만 파일로 남는다. 같은 윈도우 사용자만 풀 수 있다. */
    record WindowsDpapi(Path dir) implements SecretStore {
        private static final String PS_PREFIX = "$ErrorActionPreference='Stop';"
                + "[Console]::InputEncoding=[Text.Encoding]::UTF8;[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                + "Add-Type -AssemblyName System.Security;$in=[Console]::In.ReadToEnd();";
        private static final String PROTECT = PS_PREFIX
                + "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect("
                + "[Text.Encoding]::UTF8.GetBytes($in),$null,'CurrentUser'))";
        private static final String UNPROTECT = PS_PREFIX
                + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect("
                + "[Convert]::FromBase64String($in.Trim()),$null,'CurrentUser')))";

        @Override
        public Optional<String> get(String name) throws IOException {
            Path file = file(name);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            return Optional.of(powershell(UNPROTECT, Files.readString(file, StandardCharsets.UTF_8)));
        }

        @Override
        public void put(String name, String value) throws IOException {
            String blob = powershell(PROTECT, value).strip();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, name, ".tmp");
            Files.writeString(tmp, blob, StandardCharsets.UTF_8);
            Files.move(tmp, file(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        public void delete(String name) throws IOException {
            Files.deleteIfExists(file(name));
        }

        private Path file(String name) {
            return dir.resolve(checkName(name) + ".dpapi");
        }

        private static String powershell(String script, String stdin) throws IOException {
            return run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), stdin).ok();
        }
    }

    /** 오류 메시지에는 표준 오류만 넣는다. 표준 출력에는 비밀값이 들 수 있다. */
    record Run(int code, String out, String err) {
        String ok() throws IOException {
            if (code != 0) {
                throw new IOException("OS 보관소 명령 실패(종료 코드 " + code + "): " + err.strip());
            }
            return out;
        }
    }

    private static Run run(List<String> command, String stdin) throws IOException {
        Process p = new ProcessBuilder(command).start();
        try (OutputStream in = p.getOutputStream()) {
            in.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        // 표준 오류를 따로 읽어 파이프가 차서 멈추지 않게 한다.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread errReader = Thread.ofVirtual().start(() -> copy(p.getErrorStream(), err));
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("OS 보관소 명령 시간 초과: " + command.getFirst());
            }
            errReader.join();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("중단됨", e);
        }
        return new Run(p.exitValue(), out, err.toString(StandardCharsets.UTF_8));
    }

    private static void copy(InputStream from, ByteArrayOutputStream to) {
        try {
            from.transferTo(to);
        } catch (IOException ignored) {
            // 프로세스가 끝나면 스트림이 닫힌다.
        }
    }
}

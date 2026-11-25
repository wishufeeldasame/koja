package koja.export;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** RFC 7636 PKCE(S256). verifier는 32바이트 난수의 base64url(43자). */
record Pkce(String verifier, String challenge) {
    private static final SecureRandom RANDOM = new SecureRandom();

    static Pkce create() {
        return of(randomToken());
    }

    static Pkce of(String verifier) {
        return new Pkce(verifier, challengeOf(verifier));
    }

    static String challengeOf(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PKCE verifier와 state에 같이 쓴다. */
    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

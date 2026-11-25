package koja.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PkceTest {
    @Test
    void s256IsBase64UrlOfSha256WithoutPadding() {
        // SHA-256("abc") = ba7816bf...15ad, openssl로 따로 계산한 base64url 값.
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", Pkce.challengeOf("abc"));
    }

    @Test
    void verifierIsRandomUrlSafe43Chars() {
        Pkce a = Pkce.create();
        Pkce b = Pkce.create();
        assertTrue(a.verifier().matches("[A-Za-z0-9_-]{43}"), a.verifier());
        assertNotEquals(a.verifier(), b.verifier());
        assertEquals(Pkce.challengeOf(a.verifier()), a.challenge());
    }
}

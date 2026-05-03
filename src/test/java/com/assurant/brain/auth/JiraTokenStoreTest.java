package com.assurant.brain.auth;

import com.assurant.brain.config.properties.BrainProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JiraTokenStore — AES-256-GCM encryption")
class JiraTokenStoreTest {

    @Test
    @DisplayName("encrypt and decrypt round-trip produces original value")
    void roundTrip() {
        JiraTokenStore store = buildStore("this-is-a-32-char-encryption-key");
        String original = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.test-token-value";

        String encrypted = store.encrypt(original);
        String decrypted = store.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(original);
        assertThat(encrypted).isNotEqualTo(original);
    }

    @Test
    @DisplayName("two encryptions of same value produce different ciphertexts (random IV)")
    void uniqueCiphertexts() {
        JiraTokenStore store = buildStore("another-key-at-least-16-chars!!");
        String value = "same-value";

        String enc1 = store.encrypt(value);
        String enc2 = store.encrypt(value);

        assertThat(enc1).isNotEqualTo(enc2);
        assertThat(store.decrypt(enc1)).isEqualTo(value);
        assertThat(store.decrypt(enc2)).isEqualTo(value);
    }

    @Test
    @DisplayName("decrypt with wrong key throws")
    void wrongKeyThrows() {
        JiraTokenStore storeA = buildStore("key-a-at-least-16-characters!!!");
        JiraTokenStore storeB = buildStore("key-b-at-least-16-characters!!!");

        String encrypted = storeA.encrypt("secret");

        assertThatThrownBy(() -> storeB.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    @DisplayName("empty encryption key throws")
    void emptyKeyThrows() {
        JiraTokenStore store = buildStore("");

        assertThatThrownBy(() -> store.encrypt("test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("short encryption key throws")
    void shortKeyThrows() {
        JiraTokenStore store = buildStore("short");

        assertThatThrownBy(() -> store.encrypt("test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("null jira config throws")
    void nullJiraConfigThrows() {
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);        JiraTokenStore store = new JiraTokenStore(props);

        assertThatThrownBy(() -> store.encrypt("test"))
                .isInstanceOf(IllegalStateException.class);
    }

    private JiraTokenStore buildStore(String encryptionKey) {
        var jira = new BrainProperties.Jira(null, null, null, encryptionKey, null, "AI_DEV_", null);
        var props = new BrainProperties(null, null, null, null, null, jira, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return new JiraTokenStore(props);
    }
}

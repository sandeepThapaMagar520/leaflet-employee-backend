package com.ems.backend.outbox;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

@Service
public class OutboxCryptoService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public OutboxCryptoService(OutboxProperties properties) {
        String configured = properties.encryptionKey();
        if (configured == null || configured.length() < 32) {
            throw new IllegalStateException("OUTBOX_ENCRYPTION_KEY must contain at least 32 characters");
        }
        this.key = new SecretKeySpec(sha256(configured.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not protect outbox payload", exception);
        }
    }

    public String decrypt(byte[] ciphertext) {
        try {
            byte[] nonce = Arrays.copyOfRange(ciphertext, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(ciphertext, 12, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read protected outbox payload", exception);
        }
    }

    public String hash(String value) {
        return java.util.HexFormat.of().formatHex(sha256(value.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

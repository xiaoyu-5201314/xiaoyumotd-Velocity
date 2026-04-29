package com.xiaoyu.motd.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class CacheObf {
    private static final byte[] XOR_KEY = "XiaoyuMotdPlugin".getBytes(StandardCharsets.UTF_8);

    public static String obfuscate(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] obfuscatedBytes = xorBytes(plainBytes);
        return Base64.getEncoder().encodeToString(obfuscatedBytes);
    }

    public static String deobfuscate(String obfuscatedText) {
        if (obfuscatedText == null || obfuscatedText.isEmpty()) {
            return obfuscatedText;
        }
        try {
            byte[] obfuscatedBytes = Base64.getDecoder().decode(obfuscatedText);
            byte[] plainBytes = xorBytes(obfuscatedBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException e) {
            return obfuscatedText;
        }
    }

    private static byte[] xorBytes(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; ++i) {
            result[i] = (byte)(data[i] ^ XOR_KEY[i % XOR_KEY.length]);
        }
        return result;
    }

    public static boolean isObfuscated(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.matches("^[A-Za-z0-9+/]+=*$");
    }
}


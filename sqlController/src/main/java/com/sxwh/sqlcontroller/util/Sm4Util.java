package com.sxwh.sqlcontroller.util;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Security;

/**
 * SM4 加解密（{@code SM4/ECB/PKCS5Padding}，与 sxwh-allowance 的 Sm4 实现对齐）。
 *
 * <p>仅用于线上写入通道：把「明文 SQL + 权限后缀」加密成 hex 后再 POST 给各省 updateSql 接口。
 * 密钥只存在后端配置（{@code sql-controller.online.write.sm4-key}），任何接口都不回传。</p>
 */
public final class Sm4Util {
    private static final String TRANSFORMATION = "SM4/ECB/PKCS5Padding";
    private static final String ALGORITHM = "SM4";
    private static volatile boolean providerReady = false;

    private Sm4Util() {
    }

    private static synchronized void ensureProvider() {
        if (providerReady) return;
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        providerReady = true;
    }

    /** 明文 → hex 密文。 */
    public static String encryptHex(String keyHex, String plain) {
        SecretKeySpec key = key(keyHex);   // 密钥问题直接抛 IllegalArgumentException，不被包装
        try {
            ensureProvider();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, BouncyCastleProvider.PROVIDER_NAME);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return toHex(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SM4 加密失败：" + e.getMessage(), e);
        }
    }

    /** hex 密文 → 明文。 */
    public static String decryptHex(String keyHex, String hex) {
        SecretKeySpec key = key(keyHex);
        try {
            ensureProvider();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, BouncyCastleProvider.PROVIDER_NAME);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return new String(cipher.doFinal(fromHex(hex)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("SM4 解密失败：" + e.getMessage(), e);
        }
    }

    private static SecretKeySpec key(String keyHex) {
        if (keyHex == null || keyHex.trim().isEmpty()) throw new IllegalArgumentException("未配置 SM4 密钥");
        byte[] bytes = fromHex(keyHex.trim());
        if (bytes.length != 16) throw new IllegalArgumentException("SM4 密钥长度必须为 16 字节（hex 32 位），当前 " + bytes.length + " 字节");
        return new SecretKeySpec(bytes, ALGORITHM);
    }

    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    public static byte[] fromHex(String hex) {
        String s = hex == null ? "" : hex.trim();
        if ((s.length() & 1) == 1) throw new IllegalArgumentException("hex 长度必须为偶数");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

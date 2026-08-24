package com.example.edam.crypto.gmsm;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.crypto.digests.SM3Digest;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SM2 国密公钥算法（v3.3 W-2.1）
 *
 * 标准：GM/T 0003-2012《SM2 椭圆曲线公钥密码算法》
 * 曲线：sm2p256v1（256 bit 素数域）
 *
 * 用途：
 * - 数字签名（密钥交换 + 签名验证）
 * - 公钥加密（会话密钥封装）
 * - 身份认证（与 SM3 组合）
 *
 * 注：当前实现基于 BouncyCastle（未 GM/T 0028 认证）
 * 生产环境应使用商用国密 SDK（三未信安 / 卫士通）
 */
public final class SM2Util {

    private static final SecureRandom RNG = new SecureRandom();
    private static final X9ECParameters SM2_CURVE_PARAMS = GMNamedCurves.getByName("sm2p256v1");
    private static final ECDomainParameters SM2_SPEC = new ECDomainParameters(
        SM2_CURVE_PARAMS.getCurve(),
        SM2_CURVE_PARAMS.getG(),
        SM2_CURVE_PARAMS.getN(),
        SM2_CURVE_PARAMS.getH()
    );

    static {
        java.security.Security.addProvider(new BouncyCastleProvider());
    }

    private SM2Util() {}

    /**
     * SM2 密钥对
     */
    public static class SM2KeyPair {
        public final byte[] privateKey;  // 32 字节
        public final byte[] publicKey;   // 65 字节（04 + X + Y）

        public SM2KeyPair(byte[] privateKey, byte[] publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
    }

    /**
     * 生成 SM2 密钥对
     */
    public static SM2KeyPair generateKeyPair() {
        ECKeyGenerationParameters params = new ECKeyGenerationParameters(
            SM2_SPEC, RNG);
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(params);
        AsymmetricCipherKeyPair keyPair = generator.generateKeyPair();

        ECPrivateKeyParameters priv = (ECPrivateKeyParameters) keyPair.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) keyPair.getPublic();

        byte[] privBytes = priv.getD().toByteArray();
        if (privBytes.length > 32) {
            privBytes = Arrays.copyOfRange(privBytes, privBytes.length - 32, privBytes.length);
        } else if (privBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(privBytes, 0, padded, 32 - privBytes.length, privBytes.length);
            privBytes = padded;
        }

        // 提取公钥点（uncompressed: 04 + X + Y）
        ECPoint point = pub.getQ();
        byte[] pubBytes = point.getEncoded(false);

        return new SM2KeyPair(privBytes, pubBytes);
    }

    /**
     * SM2 签名（带 ZA 前缀，符合 GM/T 0003 标准）
     *
     * @param privateKey 私钥（32 字节）
     * @param publicKey  公钥（65 字节，含 04 前缀）
     * @param data       待签名数据
     * @return 签名（64 字节：r + s）
     */
    public static byte[] sign(byte[] privateKey, byte[] publicKey, byte[] data) {
        try {
            BigInteger d = new BigInteger(1, privateKey);
            ECPrivateKeyParameters priv = new ECPrivateKeyParameters(d, SM2_SPEC);

            // ZA = SM3(ENTL || ID || a || b || xG || yG || xA || yA)
            byte[] za = computeZa(publicKey);
            byte[] dataWithZa = new byte[za.length + data.length];
            System.arraycopy(za, 0, dataWithZa, 0, za.length);
            System.arraycopy(data, 0, dataWithZa, za.length, data.length);

            SM2Signer signer = new SM2Signer();
            signer.init(true, new ParametersWithRandom(priv, RNG));
            signer.update(dataWithZa, 0, dataWithZa.length);
            return signer.generateSignature();
        } catch (Exception e) {
            throw new RuntimeException("SM2 sign failed", e);
        }
    }

    /**
     * SM2 验签
     *
     * @param publicKey 公钥
     * @param data      数据
     * @param signature 签名
     * @return true 验证通过
     */
    public static boolean verify(byte[] publicKey, byte[] data, byte[] signature) {
        try {
            // 解析公钥点
            ECPoint point = SM2_SPEC.getCurve().decodePoint(publicKey);
            ECPublicKeyParameters pub = new ECPublicKeyParameters(point, SM2_SPEC);

            byte[] za = computeZa(publicKey);
            byte[] dataWithZa = new byte[za.length + data.length];
            System.arraycopy(za, 0, dataWithZa, 0, za.length);
            System.arraycopy(data, 0, dataWithZa, za.length, data.length);

            SM2Signer signer = new SM2Signer();
            signer.init(false, pub);
            signer.update(dataWithZa, 0, dataWithZa.length);
            return signer.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 计算 ZA = SM3(ENTL || ID || a || b || xG || yG || xA || yA)
     * 默认 ID = "1234567812345678"（16 字节 ASCII）
     */
    private static byte[] computeZa(byte[] publicKey) {
        byte[] id = "1234567812345678".getBytes();
        byte[] entl = new byte[]{(byte) (id.length * 8 >> 8 & 0xff),
                                  (byte) (id.length * 8 & 0xff)};

        org.bouncycastle.math.ec.ECCurve curve = SM2_SPEC.getCurve();
        ECPoint g = SM2_SPEC.getG();
        ECPoint point = curve.decodePoint(publicKey);

        byte[] a = curve.getA().getEncoded();
        byte[] b = curve.getB().getEncoded();
        byte[] xg = g.normalize().getAffineXCoord().getEncoded();
        byte[] yg = g.normalize().getAffineYCoord().getEncoded();
        byte[] xa = point.normalize().getAffineXCoord().getEncoded();
        byte[] ya = point.normalize().getAffineYCoord().getEncoded();

        int len = entl.length + id.length + a.length + b.length + xg.length + yg.length + xa.length + ya.length;
        byte[] combined = new byte[len];
        int pos = 0;
        System.arraycopy(entl, 0, combined, pos, entl.length); pos += entl.length;
        System.arraycopy(id, 0, combined, pos, id.length); pos += id.length;
        System.arraycopy(a, 0, combined, pos, a.length); pos += a.length;
        System.arraycopy(b, 0, combined, pos, b.length); pos += b.length;
        System.arraycopy(xg, 0, combined, pos, xg.length); pos += xg.length;
        System.arraycopy(yg, 0, combined, pos, yg.length); pos += yg.length;
        System.arraycopy(xa, 0, combined, pos, xa.length); pos += xa.length;
        System.arraycopy(ya, 0, combined, pos, ya.length); pos += ya.length;

        SM3Digest digest = new SM3Digest();
        digest.update(combined, 0, combined.length);
        byte[] za = new byte[digest.getDigestSize()];
        digest.doFinal(za, 0);
        return za;
    }
}
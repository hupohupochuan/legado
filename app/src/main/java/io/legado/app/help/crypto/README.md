https://github.com/gedoor/legado/pull/2880

非对称加密一般只持有其中一个密钥，而 Rhino JS 调用 Java 方法不能方便地传入 `null` 和 `KeyType`，因此提供重载函数。以下为当前公开重载；不支持的输入类型会抛出 `IllegalArgumentException`，不会返回 `null`。

```kotlin
fun setPublicKey(key: ByteArray): AsymmetricCrypto
fun setPublicKey(key: String): AsymmetricCrypto
fun setPrivateKey(key: ByteArray): AsymmetricCrypto
fun setPrivateKey(key: String): AsymmetricCrypto

fun decrypt(data: Any, usePublicKey: Boolean? = true): ByteArray
fun decryptStr(data: Any, usePublicKey: Boolean? = true): String

fun encrypt(data: Any, usePublicKey: Boolean? = true): ByteArray
fun encryptHex(data: Any, usePublicKey: Boolean? = true): String
fun encryptBase64(data: Any, usePublicKey: Boolean? = true): String
```

签名类 `Sign` 提供同名密钥 setter，并返回 `Sign`。对称加密的 Android/Rhino 适配位于 `SymmetricCryptoAndroid.kt`。

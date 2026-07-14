import com.android.apksig.ApkVerifier;

import java.io.File;

final class VerifyApkV4 {

    private VerifyApkV4() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: VerifyApkV4 <apk> <idsig>");
            System.exit(2);
        }

        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[0]))
                .setV4SignatureFile(new File(args[1]))
                .build()
                .verify();
        if (!result.isVerified() || !result.isVerifiedUsingV4Scheme()) {
            System.err.println("APK Signature Scheme v4 verification failed: " + args[0]);
            result.getAllErrors().forEach(error -> System.err.println("  " + error));
            System.exit(1);
        }
    }
}

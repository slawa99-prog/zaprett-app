package com.slawa99.pockettv.test;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// A separate test APK/process/UID. The production APK has no receiving activity.
public final class LogReceiptActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            Intent intent = getIntent();
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null || !"content".equals(uri.getScheme()) || intent.hasExtra(Intent.EXTRA_TEXT)
                || !uri.equals(intent.getClipData().getItemAt(0).getUri()))
                throw new IllegalStateException("Expected an attachment instead of inline text");
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192]; int n;
                while ((n = in.read(chunk)) != -1) all.write(chunk, 0, n);
                String text = new String(all.toByteArray(), StandardCharsets.UTF_8);
                if (all.size() < 1048576 || !text.startsWith("SV Pocket ") || !text.contains("QA-DIAG-FIRST")
                    || !text.endsWith("QA-DIAG-LAST")) throw new IllegalStateException("Incomplete log");
            }
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wa")) {
                throw new IllegalStateException("Unexpected write grant");
            } catch (SecurityException denied) { /* expected */ }
            Log.i("SVPocketShareQA", "FULL_READ_ONLY_FILE");
        } catch (Exception e) { Log.e("SVPocketShareQA", "Bad attachment", e); }
        finally { finish(); }
    }
}

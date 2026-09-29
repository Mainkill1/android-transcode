package dev.forma.app;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;

/** Exercises ordinary cross-app sharing from the fixture provider's separate UID/task. */
public class SharedMediaSenderActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri one = Uri.parse("content://" + getPackageName() + ".shared-media/tone-one.wav");
        getContentResolver().call(one, "reset", null, null);
        if (getIntent().getBooleanExtra("grantOnly", false)) {
            revokeUriPermission(Uri.parse("content://" + getPackageName() + ".shared-media"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            grantUriPermission(getPackageName().replaceFirst("\\.test$", ""), one, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContentResolver().call(one, "setup", getIntent().getStringExtra("setupToken"), null);
        } else if (getIntent().getBooleanExtra("revokeOnly", false)) {
            revokeUriPermission(Uri.parse("content://" + getPackageName() + ".shared-media"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            Intent shared = getIntent().getParcelableExtra("shared");
            if (shared != null) {
                // The provider owner constructs grants; the test target cannot grant an unreadable URI.
                ArrayList<Uri> streams = Intent.ACTION_SEND_MULTIPLE.equals(shared.getAction())
                    ? shared.getParcelableArrayListExtra(Intent.EXTRA_STREAM) : new ArrayList<>();
                if (Intent.ACTION_SEND.equals(shared.getAction())) {
                    Uri uri = shared.getParcelableExtra(Intent.EXTRA_STREAM);
                    if (uri != null) streams.add(uri);
                }
                if (streams != null && !streams.isEmpty()) {
                    ClipData clip = ClipData.newRawUri("Shared tones", streams.get(0));
                    for (int i = 1; i < streams.size(); i++) clip.addItem(new ClipData.Item(streams.get(i)));
                    shared.setClipData(clip);
                    shared.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                startActivity(shared); // no task/reuse flags added by the sender
            }
        }
        finish();
    }
}

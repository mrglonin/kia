package kia.app.entry;

import android.app.Activity;
import android.os.Bundle;

import kia.app.core.AppLog;
import kia.app.core.settings.AppSettings;

public final class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppSettings.applyDefaults(this);
        if (AppSettings.autoStart(this)) {
            AppLog.line(this, "USB attach: background service start");
            AppService.start(this);
        }
        finish();
    }
}

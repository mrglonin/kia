package kia.app.entry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import kia.app.core.AppLog;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.transport.usb.UsbTransport;

public final class UsbPermissionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!UsbTransport.ACTION_USB_PERMISSION.equals(intent.getAction())) return;
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
        if (granted) UsbTransport.clearPermissionRequest(context, device);
        else UsbTransport.onPermissionResult(device, false);
        AppLog.line(context, "USB permission result: " + (granted ? "granted" : "denied"));
        if (!granted) return;
        AppService.start(context);
        AdapterGateway.get(context).start();
    }
}

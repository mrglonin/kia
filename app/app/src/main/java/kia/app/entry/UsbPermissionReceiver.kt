package kia.app.entry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kia.app.core.AppLog
import kia.app.protocol.adapter.AdapterGateway
import kia.app.transport.usb.UsbTransport

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (!UsbTransport.ACTION_USB_PERMISSION.equals(intent.getAction())) return
        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        val granted: Boolean = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        if (granted) UsbTransport.clearPermissionRequest(context, device)
        else UsbTransport.onPermissionResult(device, false)
        AppLog.line(context, "USB permission result: " + (if (granted) "granted" else "denied"))
        if (!granted) return
        AppService.start(context)
        AdapterGateway.get(context).start()
    }
}

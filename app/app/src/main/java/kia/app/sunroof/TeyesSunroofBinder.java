package kia.app.sunroof;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Read-only portion of the retained TEYES system service's Binder contract. */
final class TeyesSunroofBinder {
    static final String SERVICE_DESCRIPTOR = "com.spd.system.aidl.ISpdService";
    static final String DEVICE_CLASS = "com.spd.system.entity.aidl.DeviceStatusInfo";
    private static final int REGISTER_CALLBACK = 1;
    private static final int UNREGISTER_CALLBACK = 3;
    private static final int GET_SOURCE_INFO = 9;

    private final IBinder binder;

    TeyesSunroofBinder(IBinder binder) throws RemoteException {
        if (binder == null) throw new IllegalStateException("TEYES system service unavailable");
        if (!SERVICE_DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
            throw new IllegalArgumentException("Unexpected TEYES service descriptor");
        }
        this.binder = binder;
    }

    /** getService is greylisted in the retained firmware; vendor manager methods are blocked. */
    static IBinder findService() throws ReflectiveOperationException {
        Object result = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "spd");
        return result instanceof IBinder ? (IBinder) result : null;
    }

    IBinder asBinder() {
        return binder;
    }

    void register(String packageName, IBinder callback) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeString(packageName);
            data.writeStrongBinder(callback);
            transact(REGISTER_CALLBACK, data, reply);
            requireBytes(reply, 4);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    void unregister(String packageName) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeString(packageName);
            transact(UNREGISTER_CALLBACK, data, reply);
            requireBytes(reply, 4);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    int readAcc() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeString("android");
            transact(GET_SOURCE_INFO, data, reply);
            requireBytes(reply, 4);
            reply.readException();
            requireBytes(reply, 4);
            int present = reply.readInt();
            if (present == 0) return TeyesSunroofSignals.UNKNOWN;
            if (present != 1) throw new IllegalArgumentException("Invalid TEYES wrapper presence");
            requireBytes(reply, 4);
            if (!DEVICE_CLASS.equals(reply.readString())) {
                throw new IllegalArgumentException("Unexpected TEYES device state type");
            }
            // DataWrapper writes a Parcelable class name followed by DeviceStatusInfo.
            // Its first int is platfromID, the SECOND is accStatus. The rest is unrelated
            // state: do not load a vendor CREATOR or deserialize any of those fields.
            requireBytes(reply, 8);
            reply.readInt();
            return TeyesSunroofSignals.aggregateAcc(reply.readInt());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transact(int code, Parcel data, Parcel reply) throws RemoteException {
        if (!binder.transact(code, data, reply, 0)) {
            throw new RemoteException("Unsupported TEYES service transaction " + code);
        }
    }

    private static void requireBytes(Parcel parcel, int count) {
        if (parcel.dataAvail() < count) throw new IllegalArgumentException("Truncated TEYES service reply");
    }
}

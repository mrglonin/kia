package kia.app.adas;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

final class TeyesAdasBinder {
    static final String CALLBACK_DESCRIPTOR = "com.spd.adas.library.IAdasCallback";
    private static final String SERVICE_DESCRIPTOR = "com.spd.adas.library.IAdasService";
    private static final int IS_RUNNING = 7;
    private static final int REGISTER = 8;
    private static final int UNREGISTER = 9;

    private final IBinder service;
    private final String packageName;

    TeyesAdasBinder(IBinder service, String packageName) throws RemoteException {
        if (!SERVICE_DESCRIPTOR.equals(service.getInterfaceDescriptor())) {
            throw new IllegalArgumentException("Неизвестный интерфейс сервиса TEYES ADAS");
        }
        this.service = service;
        this.packageName = packageName;
    }

    void register(IBinder callback) throws RemoteException {
        call(REGISTER, callback);
    }

    void unregister() throws RemoteException {
        call(UNREGISTER, null);
    }

    boolean running() throws RemoteException {
        return call(IS_RUNNING, null);
    }

    private boolean call(int code, IBinder callback) throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(SERVICE_DESCRIPTOR);
            if (code == REGISTER || code == UNREGISTER) request.writeString(packageName);
            if (code == REGISTER) request.writeStrongBinder(callback);
            if (!service.transact(code, request, reply, 0) || reply.dataAvail() < 4) {
                throw new RemoteException("Сервис TEYES ADAS не подтвердил запрос " + code);
            }
            reply.readException();
            if (code != IS_RUNNING) return false;
            if (reply.dataAvail() < 4) throw new RemoteException("Нет статуса распознавания");
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    static TeyesAdasFrontEvent readFrontEvent(Parcel parcel) {
        if ((parcel.dataAvail() & 3) != 0) throw new IllegalArgumentException("Неполный кадр");
        return TeyesAdasFrontEvent.read(new TeyesAdasFrontEvent.Words() {
            public int remaining() { return parcel.dataAvail() / 4; }
            public int read() { return parcel.readInt(); }
            public void skip(int count) { parcel.setDataPosition(parcel.dataPosition() + count * 4); }
        });
    }
}

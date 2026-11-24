package kia.app.sunroof

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/** Read-only portion of the retained TEYES system service's Binder contract. */
internal class TeyesSunroofBinder(binder: IBinder) {
    private val binder: IBinder

    init {
        checkNotNull(binder) { "TEYES system service unavailable" }
        require(TeyesSunroofBinder.SERVICE_DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
            "Unexpected TEYES service descriptor"
        }
        this.binder = binder
    }

    fun asBinder(): IBinder? {
        return binder
    }

    @Throws(RemoteException::class)
    fun register(packageName: String?, callback: IBinder?) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(TeyesSunroofBinder.SERVICE_DESCRIPTOR)
            data.writeString(packageName)
            data.writeStrongBinder(callback)
            transact(TeyesSunroofBinder.REGISTER_CALLBACK, data, reply)
            TeyesSunroofBinder.requireBytes(reply, 4)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @Throws(RemoteException::class)
    fun unregister(packageName: String?) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(TeyesSunroofBinder.SERVICE_DESCRIPTOR)
            data.writeString(packageName)
            transact(TeyesSunroofBinder.UNREGISTER_CALLBACK, data, reply)
            TeyesSunroofBinder.requireBytes(reply, 4)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @Throws(RemoteException::class)
    fun readAcc(): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(TeyesSunroofBinder.SERVICE_DESCRIPTOR)
            data.writeString("android")
            transact(TeyesSunroofBinder.GET_SOURCE_INFO, data, reply)
            TeyesSunroofBinder.requireBytes(reply, 4)
            reply.readException()
            TeyesSunroofBinder.requireBytes(reply, 4)
            val present: Int = reply.readInt()
            if (present == 0) return TeyesSunroofSignals.UNKNOWN
            require(present == 1) { "Invalid TEYES wrapper presence" }
            TeyesSunroofBinder.requireBytes(reply, 4)
            require(TeyesSunroofBinder.DEVICE_CLASS.equals(reply.readString())) {
                "Unexpected TEYES device state type"
            }
            // DataWrapper writes a Parcelable class name followed by DeviceStatusInfo.
            // Its first int is platfromID, the SECOND is accStatus. The rest is unrelated
            // state: do not load a vendor CREATOR or deserialize any of those fields.
            TeyesSunroofBinder.requireBytes(reply, 8)
            reply.readInt()
            return TeyesSunroofSignals.aggregateAcc(reply.readInt())
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    @Throws(RemoteException::class)
    private fun transact(code: Int, data: Parcel?, reply: Parcel?) {
        if (!binder.transact(code, data!!, reply, 0)) {
            throw RemoteException("Unsupported TEYES service transaction " + code)
        }
    }

    companion object {
        const val SERVICE_DESCRIPTOR: String = "com.spd.system.aidl.ISpdService"
        const val DEVICE_CLASS: String = "com.spd.system.entity.aidl.DeviceStatusInfo"
        private const val REGISTER_CALLBACK: Int = 1
        private const val UNREGISTER_CALLBACK: Int = 3
        private const val GET_SOURCE_INFO: Int = 9

        @JvmStatic
        /**
         * getService is greylisted in the retained firmware; vendor manager methods are blocked.
         */
        @Throws(ReflectiveOperationException::class)
        fun findService(): IBinder? {
            val result =
                Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "spd")
            return if (result is IBinder) result else null
        }

        private fun requireBytes(parcel: Parcel, count: Int) {
            require(!(parcel.dataAvail() < count)) { "Truncated TEYES service reply" }
        }
    }
}

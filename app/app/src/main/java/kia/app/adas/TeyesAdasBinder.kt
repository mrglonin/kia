package kia.app.adas

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

internal class TeyesAdasBinder(service: IBinder, packageName: String?) {
    private val service: IBinder
    private val packageName: String?

    init {
        require(TeyesAdasBinder.SERVICE_DESCRIPTOR.equals(service.getInterfaceDescriptor())) {
            "Неизвестный интерфейс сервиса TEYES ADAS"
        }
        this.service = service
        this.packageName = packageName
    }

    @Throws(RemoteException::class)
    fun register(callback: IBinder?) {
        call(TeyesAdasBinder.REGISTER, callback)
    }

    @Throws(RemoteException::class)
    fun unregister() {
        call(TeyesAdasBinder.UNREGISTER, null)
    }

    @Throws(RemoteException::class)
    fun running(): Boolean {
        return call(TeyesAdasBinder.IS_RUNNING, null)
    }

    @Throws(RemoteException::class)
    private fun call(code: Int, callback: IBinder?): Boolean {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(TeyesAdasBinder.SERVICE_DESCRIPTOR)
            if (code == TeyesAdasBinder.REGISTER || code == TeyesAdasBinder.UNREGISTER)
                request.writeString(packageName)
            if (code == TeyesAdasBinder.REGISTER) request.writeStrongBinder(callback)
            if (!service.transact(code, request, reply, 0) || reply.dataAvail() < 4) {
                throw RemoteException("Сервис TEYES ADAS не подтвердил запрос " + code)
            }
            reply.readException()
            if (code != TeyesAdasBinder.IS_RUNNING) return false
            if (reply.dataAvail() < 4) throw RemoteException("Нет статуса распознавания")
            return reply.readInt() != 0
        } finally {
            reply.recycle()
            request.recycle()
        }
    }

    companion object {
        const val CALLBACK_DESCRIPTOR: String = "com.spd.adas.library.IAdasCallback"
        private const val SERVICE_DESCRIPTOR: String = "com.spd.adas.library.IAdasService"
        private const val IS_RUNNING: Int = 7
        private const val REGISTER: Int = 8
        private const val UNREGISTER: Int = 9

        @JvmStatic
        fun readFrontEvent(parcel: Parcel): TeyesAdasFrontEvent? {
            require((parcel.dataAvail() and 3) == 0) { "Неполный кадр" }
            return TeyesAdasFrontEvent.read(
                object : TeyesAdasFrontEvent.Words {
                    override fun remaining(): Int {
                        return parcel.dataAvail() / 4
                    }

                    override fun read(): Int {
                        return parcel.readInt()
                    }

                    override fun skip(count: Int) {
                        parcel.setDataPosition(parcel.dataPosition() + count * 4)
                    }
                }
            )
        }
    }
}

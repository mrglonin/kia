package kia.app.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.regex.Pattern
import kia.app.core.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * User initiated, versioned transfer of durable settings.
 *
 * Runtime state, routes, logs, permission bookkeeping and USB permission tokens are deliberately
 * excluded. Import validates the complete document before touching preferences, accepts only known
 * keys with their exact value types, and rolls both preference files back if either durable commit
 * fails.
 */
object SettingsTransfer {
    const val FILE_NAME: String = "kia-settings.json"
    private const val FORMAT: String = "kia-settings"
    private const val FORMAT_VERSION: Int = 1
    private const val MAX_IMPORT_BYTES: Int = 1024 * 1024
    private const val MAX_ENTRIES_PER_FILE: Int = 512
    private const val MAX_KEY_LENGTH: Int = 160
    private const val MAX_STRING_LENGTH: Int = 4096
    private const val MAX_STRING_SET_ITEMS: Int = 128
    private const val PREFS_APP: String = "Kia"
    private const val PREFS_RADIO: String = "kia_radio_stations"
    private const val KEY_SETTINGS_SCHEMA: String = "schema"
    private const val KEY_LAST_UPDATE_CHECK_AT: String = "last_update_check_at"
    private val IMPORT_LOCK: Any = Object()

    private val ALLOWED_FILES: Set<String?>? =
        SettingsTransfer.names(SettingsTransfer.PREFS_APP, SettingsTransfer.PREFS_RADIO)
    private val TOP_LEVEL_KEYS: Set<String?>? =
        SettingsTransfer.names("format", "version", "created_at", "preferences")

    private val APP_BOOLEAN_KEYS: Set<String?>? =
        SettingsTransfer.names(
            "auto_start",
            "adas_enabled",
            "sunroof_overlay",
            "sunroof_parking_warning",
            "sunroof_sound",
            "sunroof_transition_sound",
            "media_enabled",
            "media_profile_configured",
            "call_enabled",
            "media_overlay",
            "navigation_enabled",
            "compass_enabled",
            "compass_force",
            "compass_kia_navi_format",
            "nav_tbt",
            "nav_finish_direction",
            "nav_finish_compass_auto",
            "nav_overspeed_text",
            "nav_speed_limit_route_only",
            "nav_speed_limit_repeat_enabled",
            "nav_overlay",
            "nav_micro_maneuvers",
            "nav_straight_until_main",
            "nav_debug_visible",
            "amp_enabled",
            "canbus_debug_visible",
            "tpms_alerts",
            "tpms_sound_alerts",
            "teyes_tpms_widget_forward_enabled",
            "rcta_overlay",
            "rcta_sound",
            "media_source_reassert",
            "media_tab_visible",
            "expert_mode",
        )

    private val APP_INT_KEYS: Set<String?>? =
        SettingsTransfer.names(
            "media_profile",
            "nav_output_mode",
            "nav_finish_direction_lead_meters",
            "nav_text_mode",
            "nav_maneuver_text_seconds",
            "nav_source_mode",
            "nav_eta_time_mode",
            "nav_micro_hold_seconds",
            "nav_micro_max_distance_meters",
            "nav_main_reveal_distance_meters",
            "sas_ratio",
            "tpms_low_pressure",
            "tpms_high_pressure",
            "tpms_low_temp",
            "tpms_high_temp",
            "rcta_style",
            "rcta_sound_mode",
            "rcta_color",
            "rcta_bg_alpha",
            "rcta_arrow_count",
            "other_media_source_mode",
            "media_text_mode",
            "media_android_source_delay_ms",
            "media_uart_source_delay_ms",
            "media_artist_delay_ms",
            "call_source_mode",
            "canbus_temp_source",
            "firmware_source",
        )

    private val APP_STRING_KEYS: Set<String?>? =
        SettingsTransfer.names("media_preferred_session_package")
    private val APP_STRING_SET_KEYS: Set<String?>? =
        SettingsTransfer.names("media_blocked_session_packages")

    private val RADIO_KEY: Pattern? =
        Pattern.compile("^(?:station|manual|origin)_(?:FM|AM)_[0-9]{2,5}(?:\\.[0-9]{1,2})?$")

    @JvmStatic
    @Throws(Exception::class)
    fun write(context: Context?, target: Uri?) {
        require(!(context == null || target == null)) { "target" }
        context.getContentResolver().openOutputStream(target, "wt").use { output ->
            checkNotNull(output) { "Cannot open output" }
            output.write(SettingsTransfer.exportJson(context)!!.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun read(context: Context?, source: Uri?): ImportResult {
        require(!(context == null || source == null)) { "source" }
        AppSettings.applyDefaults(context)
        val bytes: ByteArray?
        context.getContentResolver().openInputStream(source).use { input ->
            checkNotNull(input) { "Cannot open input" }
            bytes = SettingsTransfer.readBounded(input)
        }
        val root: JSONObject = JSONObject(String(bytes!!, StandardCharsets.UTF_8))
        val validated: ValidatedImport =
            SettingsTransfer.validateJsonDocument(
                root,
                SettingsTransfer.currentSettingsSchema(context),
            )
        val result: ImportResult = SettingsTransfer.applyValidated(context, validated)
        // The imported schema marker is intentionally never written. Keep the running app on its
        // current schema and only let its normal defaults repair any settings absent in the file.
        AppSettings.applyDefaults(context)
        return result
    }

    @JvmStatic
    @Throws(Exception::class)
    fun exportJson(context: Context?): String? {
        requireNotNull(context) { "context" }
        AppSettings.applyDefaults(context)
        val root: JSONObject = JSONObject()
        root.put("format", SettingsTransfer.FORMAT)
        root.put("version", SettingsTransfer.FORMAT_VERSION)
        root.put(
            "created_at",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
        )
        val files: JSONObject = JSONObject()
        for (fileName in SettingsTransfer.ALLOWED_FILES!!) {
            files.put(fileName, SettingsTransfer.exportPreferences(context, fileName))
        }
        root.put("preferences", files)
        return root.toString(2)
    }

    @Throws(Exception::class)
    private fun exportPreferences(context: Context, name: String?): JSONObject? {
        val out: JSONObject = JSONObject()
        for (entry in context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entries) {
            val key = entry.key
            val value = entry.value
            val expected = SettingsTransfer.expectedType(name, key)
            if (expected == null || !SettingsTransfer.matchesStoredType(expected, value)) continue
            if (value is Set<*>) {
                val array: JSONArray = JSONArray()
                for (item: Any? in value as Set<*>) {
                    if (item is String) array.put(item)
                }
                out.put(key, array)
            } else {
                out.put(key, value)
            }
        }
        return out
    }

    @Throws(Exception::class)
    private fun validateJsonDocument(root: JSONObject?, currentSchema: Int): ValidatedImport {
        requireNotNull(root) { "Пустой файл настроек" }
        SettingsTransfer.rejectUnknownJsonKeys(
            root,
            SettingsTransfer.TOP_LEVEL_KEYS!!,
            "корне файла",
        )

        val format = SettingsTransfer.required(root, "format")
        require(!(format !is String || !SettingsTransfer.FORMAT.equals(format))) {
            "Это не файл настроек Kia"
        }
        val version: Int =
            SettingsTransfer.requireInt(SettingsTransfer.required(root, "version"), "version")
        require(!(version < 1 || version > SettingsTransfer.FORMAT_VERSION)) {
            "Неподдерживаемая версия настроек: " + version
        }
        if (root.has("created_at")) {
            val createdAt = root.get("created_at")
            require(
                !(createdAt !is String || (createdAt).length > SettingsTransfer.MAX_STRING_LENGTH)
            ) {
                "Неверный тип поля created_at"
            }
        }

        val rawPreferences = SettingsTransfer.required(root, "preferences")
        require(rawPreferences is JSONObject) { "В файле нет настроек" }
        val preferenceFiles: JSONObject = rawPreferences
        val rawFiles: LinkedHashMap<String?, Map<String?, *>?> = LinkedHashMap()
        val fileNames: Iterator<String?>? = preferenceFiles.keys()
        while (fileNames!!.hasNext()) {
            val fileName = fileNames.next()
            require(SettingsTransfer.ALLOWED_FILES!!.contains(fileName)) {
                "Неизвестный раздел настроек: " + SettingsTransfer.safeLabel(fileName)
            }
            val rawValues = preferenceFiles.get(fileName)
            require(rawValues is JSONObject) {
                "Неверный раздел настроек: " + SettingsTransfer.safeLabel(fileName)
            }
            rawFiles.put(fileName, SettingsTransfer.jsonValues(rawValues))
        }
        return SettingsTransfer.validatePreferenceMaps(rawFiles, currentSchema)
    }

    @JvmStatic
    /** Pure validation boundary used by the JSON reader and focused local tests. */
    fun validatePreferenceMaps(
        preferenceFiles: Map<String?, out Map<String?, *>?>?,
        currentSchema: Int,
    ): ValidatedImport {
        require(currentSchema >= 1) { "Текущая схема настроек повреждена" }
        requireNotNull(preferenceFiles) { "В файле нет настроек" }

        val acceptedFiles: LinkedHashMap<String?, Map<String?, Any?>?> = LinkedHashMap()
        var entries: Int = 0
        for (file in preferenceFiles.entries) {
            val fileName = file.key
            require(SettingsTransfer.ALLOWED_FILES!!.contains(fileName)) {
                "Неизвестный раздел настроек: " + SettingsTransfer.safeLabel(fileName)
            }
            val values: Map<String?, *>? = file.value
            requireNotNull(values) {
                "Неверный раздел настроек: " + SettingsTransfer.safeLabel(fileName)
            }
            require(!(values.size > SettingsTransfer.MAX_ENTRIES_PER_FILE)) {
                "Слишком много параметров"
            }

            val accepted: LinkedHashMap<String?, Any?> = LinkedHashMap()
            for (entry in values.entries) {
                val key = entry.key
                require(
                    !(key == null || key.isEmpty() || key.length > SettingsTransfer.MAX_KEY_LENGTH)
                ) {
                    "Неверное имя параметра"
                }
                val value = entry.value

                if (
                    SettingsTransfer.PREFS_APP.equals(fileName) &&
                        SettingsTransfer.KEY_SETTINGS_SCHEMA.equals(key)
                ) {
                    val sourceSchema: Int =
                        SettingsTransfer.requireInt(value, SettingsTransfer.PREFS_APP + "." + key)
                    require(!(sourceSchema < 1 || sourceSchema > currentSchema)) {
                        "Несовместимая схема настроек: " + sourceSchema
                    }
                    continue
                }
                if (
                    SettingsTransfer.PREFS_APP.equals(fileName) &&
                        SettingsTransfer.KEY_LAST_UPDATE_CHECK_AT.equals(key)
                ) {
                    // Older v1 exports contained this runtime timestamp. Validate it for backward
                    // compatibility, but never copy it to another device.
                    val timestamp: Long =
                        SettingsTransfer.requireLong(value, SettingsTransfer.PREFS_APP + "." + key)
                    require(timestamp >= 0L) {
                        "Неверное значение: " + SettingsTransfer.PREFS_APP + "." + key
                    }
                    continue
                }

                val expected = SettingsTransfer.expectedType(fileName, key)
                requireNotNull(expected) {
                    "Неизвестный параметр: " +
                        SettingsTransfer.safeLabel(fileName.toString() + "." + key)
                }
                accepted.put(
                    key,
                    SettingsTransfer.validateValue(
                        expected,
                        value!!,
                        fileName.toString() + "." + key,
                    ),
                )
                entries++
            }
            if (!accepted.isEmpty()) acceptedFiles.put(fileName, accepted)
        }
        require(entries != 0) { "Файл настроек пуст" }
        return ValidatedImport(acceptedFiles, entries)
    }

    @JvmStatic
    /**
     * Applies a fully validated plan. Android has no transaction spanning two SharedPreferences
     * files, so both snapshots are retained and restored if either atomic per-file commit fails.
     */
    fun applyValidated(context: Context?, validated: ValidatedImport?): ImportResult {
        require(!(context == null || validated == null)) { "settings" }
        synchronized(SettingsTransfer.IMPORT_LOCK) {
            val snapshots: LinkedHashMap<String?, Map<String?, Any?>?> = LinkedHashMap()
            for (fileName: String? in validated.files!!.keys) {
                snapshots.put(
                    fileName,
                    SettingsTransfer.copyValues(
                        context.getSharedPreferences(fileName, Context.MODE_PRIVATE).getAll()
                    ),
                )
            }

            try {
                for (file in validated.files.entries) {
                    val prefs: SharedPreferences? =
                        context.getSharedPreferences(file.key, Context.MODE_PRIVATE)
                    val editor = prefs!!.edit()
                    SettingsTransfer.putValues(editor!!, file.value!!)
                    check(editor.commit()) { "Не удалось сохранить раздел " + file.key }
                }
            } catch (error: RuntimeException) {
                val restored: Boolean = SettingsTransfer.restoreSnapshots(context, snapshots)
                if (!restored) {
                    throw IllegalStateException(
                        "Импорт отменён, но не удалось восстановить исходные настройки",
                        error,
                    )
                }
                throw error
            }
            return ImportResult(validated.files.size, validated.entries)
        }
    }

    @Throws(Exception::class)
    private fun jsonValues(values: JSONObject): Map<String?, Any?>? {
        require(!(values.length() > SettingsTransfer.MAX_ENTRIES_PER_FILE)) {
            "Слишком много параметров"
        }
        val out: LinkedHashMap<String?, Any?> = LinkedHashMap()
        val keys: Iterator<String?>? = values.keys()
        while (keys!!.hasNext()) {
            val key = keys.next()
            var value: Any? = values.get(key)
            if (value is JSONArray) {
                val array: JSONArray = value
                val items: ArrayList<Any?> = ArrayList(array.length())
                for (index in 0..<array.length()) {
                    items.add(array.get(index))
                }
                value = items
            }
            out.put(key, if (value === JSONObject.NULL) null else value)
        }
        return out
    }

    private fun validateValue(expected: ValueType, value: Any, label: String?): Any? {
        when (expected) {
            ValueType.BOOLEAN -> if (value is Boolean) return value
            ValueType.INT -> return SettingsTransfer.requireInt(value, label)
            ValueType.STRING ->
                if (value is String && (value).length <= SettingsTransfer.MAX_STRING_LENGTH) {
                    require(
                        !("media_preferred_session_package"
                            .equals(SettingsTransfer.keyPart(label)) &&
                            !SettingsTransfer.validPackageName(value))
                    ) {
                        "Неверное значение: " + SettingsTransfer.safeLabel(label)
                    }
                    require(
                        !(SettingsTransfer.PREFS_RADIO.equals(SettingsTransfer.filePart(label)) &&
                            SettingsTransfer.keyPart(label)!!.startsWith("origin_") &&
                            !SettingsTransfer.validRadioOrigin(value))
                    ) {
                        "Неверное значение: " + SettingsTransfer.safeLabel(label)
                    }
                    return value
                }
            ValueType.STRING_SET -> return SettingsTransfer.requireStringSet(value, label)
            else -> {}
        }
        throw IllegalArgumentException(
            "Неверный тип параметра: " + SettingsTransfer.safeLabel(label)
        )
    }

    private fun requireStringSet(value: Any, label: String?): Set<String?>? {
        val items: Iterable<*>?
        val size: Int
        if (value is List<*>) {
            items = value as List<*>
            size = (value as List<*>).size
        } else if (value is Set<*>) {
            items = value as Set<*>?
            size = (value as Set<*>).size
        } else {
            throw IllegalArgumentException(
                "Неверный тип параметра: " + SettingsTransfer.safeLabel(label)
            )
        }
        require(size <= SettingsTransfer.MAX_STRING_SET_ITEMS) {
            "Слишком много значений: " + SettingsTransfer.safeLabel(label)
        }
        val out: LinkedHashSet<String?> = LinkedHashSet()
        for (item in items!!) {
            require(
                !((item !is String) ||
                    (item).length > SettingsTransfer.MAX_STRING_LENGTH ||
                    !SettingsTransfer.validPackageName(item))
            ) {
                "Неверное значение: " + SettingsTransfer.safeLabel(label)
            }
            out.add(item)
        }
        return Collections.unmodifiableSet(out)
    }

    private fun requireInt(value: Any?, label: String?): Int {
        require(!(value !is Int && value !is Long)) {
            "Неверный тип параметра: " + SettingsTransfer.safeLabel(label)
        }
        val number: Long = (value as Number).toLong()
        require(!(number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)) {
            "Значение вне диапазона: " + SettingsTransfer.safeLabel(label)
        }
        return number.toInt()
    }

    private fun requireLong(value: Any?, label: String?): Long {
        require(!(value !is Int && value !is Long)) {
            "Неверный тип параметра: " + SettingsTransfer.safeLabel(label)
        }
        return (value as Number).toLong()
    }

    private fun expectedType(fileName: String?, key: String?): ValueType? {
        if (fileName == null || key == null) return null
        if (SettingsTransfer.PREFS_RADIO.equals(fileName)) {
            return if (SettingsTransfer.RADIO_KEY!!.matcher(key).matches()) ValueType.STRING
            else null
        }
        if (!SettingsTransfer.PREFS_APP.equals(fileName)) return null
        if (SettingsTransfer.APP_BOOLEAN_KEYS!!.contains(key)) return ValueType.BOOLEAN
        if (SettingsTransfer.APP_INT_KEYS!!.contains(key)) return ValueType.INT
        if (SettingsTransfer.APP_STRING_KEYS!!.contains(key)) return ValueType.STRING
        if (SettingsTransfer.APP_STRING_SET_KEYS!!.contains(key)) return ValueType.STRING_SET
        return null
    }

    private fun matchesStoredType(expected: ValueType, value: Any?): Boolean {
        if (value == null) return false
        when (expected) {
            ValueType.BOOLEAN -> return value is Boolean
            ValueType.INT -> return value is Int
            ValueType.STRING ->
                return value is String && (value).length <= SettingsTransfer.MAX_STRING_LENGTH
            ValueType.STRING_SET -> {
                if (
                    value !is Set<*> ||
                        (value as Set<*>).size > SettingsTransfer.MAX_STRING_SET_ITEMS
                ) {
                    return false
                }
                for (item: Any? in value as Set<*>) {
                    if (item !is String || (item).length > SettingsTransfer.MAX_STRING_LENGTH)
                        return false
                }
                return true
            }
            else -> return false
        }
    }

    private fun currentSettingsSchema(context: Context): Int {
        val value =
            context
                .getSharedPreferences(SettingsTransfer.PREFS_APP, Context.MODE_PRIVATE)
                .getAll()
                .get(SettingsTransfer.KEY_SETTINGS_SCHEMA)
        check(!(value !is Int || (value) < 1)) { "Текущая схема настроек повреждена" }
        return value
    }

    @Throws(Exception::class)
    private fun rejectUnknownJsonKeys(`object`: JSONObject, allowed: Set<String?>, where: String?) {
        val keys: Iterator<String?>? = `object`.keys()
        while (keys!!.hasNext()) {
            val key = keys.next()
            require(allowed.contains(key)) {
                "Неизвестное поле " + SettingsTransfer.safeLabel(key) + " в " + where
            }
        }
    }

    @Throws(Exception::class)
    private fun required(`object`: JSONObject, key: String?): Any? {
        require(`object`.has(key)) { "Отсутствует поле " + key }
        return `object`.get(key)
    }

    private fun copyValues(values: Map<String?, *>?): Map<String?, Any?>? {
        val out: LinkedHashMap<String?, Any?> = LinkedHashMap()
        if (values == null) return out
        for (entry in values.entries) {
            var value: Any? = entry.value
            if (value is Set<*>) {
                value = LinkedHashSet(value as Set<*>)
            }
            out.put(entry.key, value)
        }
        return out
    }

    private fun putValues(editor: SharedPreferences.Editor, values: Map<String?, *>) {
        for (entry in values.entries) {
            SettingsTransfer.putValue(editor, entry.key, entry.value)
        }
    }

    @SuppressWarnings("unchecked")
    private fun putValue(editor: SharedPreferences.Editor, key: String?, value: Any?) {
        if (value is Boolean) {
            editor.putBoolean(key, value)
        } else if (value is Int) {
            editor.putInt(key, value)
        } else if (value is Long) {
            editor.putLong(key, (value as Long?)!!)
        } else if (value is Float) {
            editor.putFloat(key, (value as Float?)!!)
        } else if (value is String) {
            editor.putString(key, value as String?)
        } else if (value is Set<*>) {
            editor.putStringSet(key, LinkedHashSet(value as Set<String?>?))
        } else {
            throw IllegalArgumentException(
                "Неподдерживаемый тип: " + SettingsTransfer.safeLabel(key)
            )
        }
    }

    private fun restoreSnapshots(
        context: Context,
        snapshots: Map<String?, Map<String?, Any?>?>,
    ): Boolean {
        var restored: Boolean = true
        for (snapshot in snapshots.entries) {
            try {
                val editor: SharedPreferences.Editor? =
                    context.getSharedPreferences(snapshot.key, Context.MODE_PRIVATE).edit().clear()
                SettingsTransfer.putValues(editor!!, snapshot.value!!)
                if (!editor.commit()) restored = false
            } catch (error: RuntimeException) {
                restored = false
            }
        }
        return restored
    }

    private fun validPackageName(value: String?): Boolean {
        if (value == null) return false
        val clean = value.trim().lowercase(Locale.US)
        if (clean.isEmpty()) return value.trim().isEmpty()
        if (clean.length > 200 || !clean.equals(value.trim().lowercase(Locale.US))) return false
        for (index in 0..<clean.length) {
            val c: Char = clean.get(index)
            val allowed: Boolean =
                c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '_'
            if (!allowed) return false
        }
        return true
    }

    private fun validRadioOrigin(value: String?): Boolean {
        return "manual".equals(value) || "learned".equals(value) || "fallback".equals(value)
    }

    private fun filePart(label: String?): String? {
        val split: Int = if (label == null) -1 else label.indexOf('.')
        return if (split < 0) "" else label!!.substring(0, split)
    }

    private fun keyPart(label: String?): String? {
        val split: Int = if (label == null) -1 else label.indexOf('.')
        return if (split < 0) "" else label!!.substring(split + 1)
    }

    private fun safeLabel(value: String?): String? {
        if (value == null) return ""
        val clean = value.replace('\n', ' ').replace('\r', ' ').trim()
        return if (clean.length <= 180) clean else clean.substring(0, 180)
    }

    private fun names(vararg values: String?): Set<String?>? {
        return Collections.unmodifiableSet(LinkedHashSet(values.asList()))
    }

    @Throws(Exception::class)
    private fun readBounded(input: InputStream): ByteArray? {
        val output: ByteArrayOutputStream = ByteArrayOutputStream()
        val buffer: ByteArray = ByteArray(8192)
        var total: Int = 0
        var read: Int
        while ((input.read(buffer).also { read = it }) != -1) {
            total += read
            require(total <= SettingsTransfer.MAX_IMPORT_BYTES) { "Файл настроек больше 1 МБ" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    internal enum class ValueType {
        BOOLEAN,
        INT,
        STRING,
        STRING_SET,
    }

    class ValidatedImport
    internal constructor(files: Map<String?, Map<String?, Any?>?>, entries: Int) {
        @JvmField val files: Map<String?, Map<String?, Any?>?>?
        @JvmField val entries: Int

        init {
            val copy: LinkedHashMap<String?, Map<String?, Any?>?> = LinkedHashMap()
            for (file in files.entries) {
                copy.put(file.key, Collections.unmodifiableMap(LinkedHashMap(file.value)))
            }
            this.files = Collections.unmodifiableMap(copy)
            this.entries = entries
        }
    }

    class ImportResult internal constructor(files: Int, entries: Int) {
        @JvmField val files: Int
        @JvmField val entries: Int

        init {
            this.files = files
            this.entries = entries
        }

        fun summary(): String? {
            return "Импортировано параметров: " + entries
        }
    }
}

package dev.hadapkar.carport.patcher

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType

/**
 * Adds the declarations Android Auto looks for when listing a phone activity as a car app.
 * Mirrors the set used by AA Browser (github.com/kododake/AABrowser), which is known to
 * project on Android 15+ with Android Auto "Unknown sources" enabled.
 *
 * Every step is idempotent, so patching an already patched app changes nothing twice.
 */
internal class ManifestPatcher(
    private val manifest: AndroidManifestBlock,
    private val options: PatchOptions,
) {
    val changes = mutableListOf<String>()

    /**
     * @param automotiveDescResId called only when the app does not already declare
     *   com.google.android.gms.car.application; returns the id of an
     *   `<automotiveApp>` xml resource to point it at.
     */
    fun patch(automotiveDescResId: () -> Int) {
        val application = manifest.applicationElement
            ?: throw PatchException("The manifest has no <application> element.")

        val launchers = findLaunchers()
        if (launchers.isEmpty()) {
            throw PatchException("This app has no launcher activity, so there is nothing to open on the car screen.")
        }
        // Read before the marker is (re)written below.
        val previouslyPatched = findMeta(application, META_CARPORT_PATCHED) != null
        val carLaunchers = selectCarLaunchers(launchers)

        setMetaBoolean(application, META_DISTRACTION_OPTIMIZED, true)
        setMetaFloat(application, META_MAX_ASPECT, APPLICATION_MAX_ASPECT)
        if (findMeta(application, META_MIN_CAR_API_LEVEL) == null) {
            setMetaInt(application, META_MIN_CAR_API_LEVEL, MIN_CAR_API_LEVEL)
        }
        if (findMeta(application, META_GMS_CAR_APPLICATION) == null) {
            setMetaResource(application, META_GMS_CAR_APPLICATION, automotiveDescResId())
            changes += "Declared the app to Android Auto"
        }
        setMetaString(application, META_CARPORT_PATCHED, PATCH_FORMAT_VERSION)
        // Put back what an earlier patch changed, so options turned off since then are undone.
        restoreOriginals(application)
        if (options.forceResizeable) {
            setResizeable(application)
        }
        // Android Auto lists sideloaded ordinary apps only through its parked-app ("native app")
        // path, which Google documents for apps in the game category (AA Browser does the same).
        // The category only labels the app; it doesn't change how it runs.
        if (rememberOriginal(application, TRACKED_APP_CATEGORY)) {
            application.getOrCreateAndroidAttribute(ATTR_APP_CATEGORY, AndroidAttr.APP_CATEGORY).apply {
                setValueType(ValueType.DEC)
                setData(AndroidAttr.APP_CATEGORY_GAME)
            }
        }
        // Declared by Google's car app library in AA Browser; plain install-time permissions.
        CAR_PERMISSIONS.forEach { manifest.addUsesPermission(it) }

        for (launcher in launchers) {
            if (launcher in carLaunchers) {
                launcher.filters.forEach { filter -> CAR_CATEGORIES.forEach { ensureCategory(filter, it) } }
            } else if (previouslyPatched) {
                // Undo an earlier CarPort patch's icon on entries no longer chosen (e.g. the
                // "every launcher icon" option was turned off). Never touch an unpatched app's own entries.
                launcher.filters.forEach { filter -> CAR_CATEGORIES.forEach { removeCategory(filter, it) } }
            }
        }
        changes += if (carLaunchers.size == 1) {
            "Added a car icon for ${carLaunchers.single().name.substringAfterLast('.')}" +
                if (launchers.size > 1) " (1 of ${launchers.size} launcher entries)" else ""
        } else {
            "Added car icons for ${carLaunchers.size} of ${launchers.size} launcher entries"
        }

        val activities = application.listElements(TAG_ACTIVITY)
        for (activity in activities) {
            setMetaBoolean(activity, META_DISTRACTION_OPTIMIZED, true)
            setMetaFloat(activity, META_MAX_ASPECT, ACTIVITY_MAX_ASPECT)
            restoreOriginals(activity)
            if (options.forceResizeable) {
                setResizeable(activity)
            }
            if (options.unlockOrientation &&
                activity.searchAttributeByResourceId(AndroidAttr.SCREEN_ORIENTATION) != null &&
                rememberOriginal(activity, TRACKED_ORIENTATION)
            ) {
                activity.removeAttributesWithId(AndroidAttr.SCREEN_ORIENTATION)
            }
            if (options.keepStateOnScreenChange) {
                addScreenSwitchConfigChanges(activity)
            }
        }
        // Aliases carry their own meta-data in the resolved ActivityInfo.
        for (alias in application.listElements(TAG_ACTIVITY_ALIAS)) {
            setMetaBoolean(alias, META_DISTRACTION_OPTIMIZED, true)
        }
        changes += "Marked ${activities.size} activities as car-display ready"
        if (options.unlockOrientation) {
            changes += "Removed orientation locks"
        }
        if (options.keepStateOnScreenChange) {
            changes += "Kept pages open when switching between phone and car screen"
        }
    }

    /** An activity or alias with at least one MAIN + LAUNCHER intent filter. */
    private class Launcher(val name: String, val enabledInManifest: Boolean, val filters: List<ResXmlElement>)

    private fun findLaunchers(): List<Launcher> {
        val application = manifest.applicationElement ?: return emptyList()
        val components = application.listElements(TAG_ACTIVITY) + application.listElements(TAG_ACTIVITY_ALIAS)
        return components.mapNotNull { component ->
            val filters = component.listElements(TAG_INTENT_FILTER).filter { filter ->
                filter.listElements(TAG_ACTION).any { it.androidName() == ACTION_MAIN } &&
                    filter.listElements(TAG_CATEGORY).any { it.androidName() == CATEGORY_LAUNCHER }
            }
            if (filters.isEmpty()) return@mapNotNull null
            val name = component.androidName()?.let(manifest::fullClassName) ?: return@mapNotNull null
            Launcher(name, isEnabledInManifest(component), filters)
        }
    }

    /**
     * Which entries get a car icon: the ones the phone launcher shows, so apps with alternate
     * icons (Firefox has 23 disabled ones) get one car icon. Falls back to every entry whenever
     * that can't be worked out, so a car icon is never lost.
     */
    private fun selectCarLaunchers(launchers: List<Launcher>): List<Launcher> {
        if (options.allLauncherEntries) return launchers
        val preferred = launchers.filter { it.name in options.preferredLauncherComponents }
        if (preferred.isNotEmpty()) return preferred
        return launchers.filter { it.enabledInManifest }.ifEmpty { launchers }
    }

    /** android:enabled is true unless it is the literal false; a resource reference counts as enabled. */
    private fun isEnabledInManifest(component: ResXmlElement): Boolean {
        val enabled = component.searchAttributeByResourceId(AndroidAttr.ENABLED) ?: return true
        return enabled.valueType != ValueType.BOOLEAN || enabled.data != 0
    }

    private fun ensureCategory(filter: ResXmlElement, name: String) {
        if (filter.listElements(TAG_CATEGORY).any { it.androidName() == name }) return
        filter.createChildElement(TAG_CATEGORY)
            .getOrCreateAndroidAttribute(ATTR_NAME, AndroidAttr.NAME)
            .setValueAsString(name)
    }

    private fun removeCategory(filter: ResXmlElement, name: String) {
        filter.listElements(TAG_CATEGORY).filter { it.androidName() == name }.forEach { it.removeSelf() }
    }

    /**
     * ORs the screen-switch flags into android:configChanges, keeping the app's own flags.
     * A value that is a resource reference is left alone, since its flags can't be read here.
     */
    private fun addScreenSwitchConfigChanges(activity: ResXmlElement) {
        if (!rememberOriginal(activity, TRACKED_CONFIG_CHANGES)) return
        val existing = activity.searchAttributeByResourceId(AndroidAttr.CONFIG_CHANGES)
        when {
            existing == null -> activity.getOrCreateAndroidAttribute(ATTR_CONFIG_CHANGES, AndroidAttr.CONFIG_CHANGES).apply {
                setValueType(ValueType.HEX)
                setData(AndroidAttr.CONFIG_CHANGES_SCREEN_SWITCH)
            }
            existing.valueType == ValueType.HEX || existing.valueType == ValueType.DEC ->
                existing.setData(existing.data or AndroidAttr.CONFIG_CHANGES_SCREEN_SWITCH)
        }
    }

    private fun setResizeable(element: ResXmlElement) {
        if (!rememberOriginal(element, TRACKED_RESIZEABLE)) return
        element.getOrCreateAndroidAttribute(ATTR_RESIZEABLE_ACTIVITY, AndroidAttr.RESIZEABLE_ACTIVITY)
            .setValueAsBoolean(true)
    }

    /** An attribute CarPort may change, and the meta-data key that stores its original value. */
    private class Tracked(val attrName: String, val attrId: Int) {
        val metaName = "$META_ORIGINAL_PREFIX$attrName"
    }

    /**
     * Stores the attribute's current value in meta-data before CarPort first changes it:
     * "absent" or "<type>:<data>". Returns false (do not change it) for string-typed values,
     * which can't be stored and restored this way.
     */
    private fun rememberOriginal(element: ResXmlElement, tracked: Tracked): Boolean {
        val attribute = element.searchAttributeByResourceId(tracked.attrId)
        if (attribute != null && attribute.valueType == ValueType.STRING) return false
        if (findMeta(element, tracked.metaName) != null) return true // Already the true original.
        val original = if (attribute == null) ORIGINAL_ABSENT else "${attribute.valueType.byte}:${attribute.data}"
        setMetaString(element, tracked.metaName, original)
        return true
    }

    /** Undoes every change recorded by [rememberOriginal] and drops the records. */
    private fun restoreOriginals(element: ResXmlElement) {
        for (tracked in TRACKED) {
            val meta = findMeta(element, tracked.metaName) ?: continue
            val original = meta.searchAttributeByResourceId(AndroidAttr.VALUE)?.valueAsString
            val parts = original?.split(':')
            if (original == ORIGINAL_ABSENT) {
                element.removeAttributesWithId(tracked.attrId)
            } else if (parts?.size == 2 && parts[0].toByteOrNull() != null && parts[1].toIntOrNull() != null) {
                element.getOrCreateAndroidAttribute(tracked.attrName, tracked.attrId).apply {
                    setValueType(ValueType.valueOf(parts[0].toByte()))
                    setData(parts[1].toInt())
                }
            }
            meta.removeSelf()
        }
    }

    private fun findMeta(parent: ResXmlElement, name: String): ResXmlElement? =
        parent.listElements(TAG_META_DATA).firstOrNull { it.androidName() == name }

    private fun getOrCreateMeta(parent: ResXmlElement, name: String): ResXmlElement =
        findMeta(parent, name) ?: parent.createChildElement(TAG_META_DATA).also {
            it.getOrCreateAndroidAttribute(ATTR_NAME, AndroidAttr.NAME).setValueAsString(name)
        }

    /** android:value and android:resource are mutually exclusive on <meta-data>. */
    private fun valueAttribute(parent: ResXmlElement, name: String): ResXmlAttribute {
        val meta = getOrCreateMeta(parent, name)
        meta.removeAttributesWithId(AndroidAttr.RESOURCE)
        return meta.getOrCreateAndroidAttribute(ATTR_VALUE, AndroidAttr.VALUE)
    }

    private fun setMetaBoolean(parent: ResXmlElement, name: String, value: Boolean) {
        valueAttribute(parent, name).setValueAsBoolean(value)
    }

    private fun setMetaString(parent: ResXmlElement, name: String, value: String) {
        valueAttribute(parent, name).setValueAsString(value)
    }

    private fun setMetaInt(parent: ResXmlElement, name: String, value: Int) {
        valueAttribute(parent, name).apply {
            setValueType(ValueType.DEC)
            setData(value)
        }
    }

    private fun setMetaFloat(parent: ResXmlElement, name: String, value: Float) {
        valueAttribute(parent, name).apply {
            setValueType(ValueType.FLOAT)
            setData(java.lang.Float.floatToRawIntBits(value))
        }
    }

    private fun setMetaResource(parent: ResXmlElement, name: String, resourceId: Int) {
        val meta = getOrCreateMeta(parent, name)
        meta.removeAttributesWithId(AndroidAttr.VALUE)
        meta.getOrCreateAndroidAttribute(ATTR_RESOURCE, AndroidAttr.RESOURCE).apply {
            setValueType(ValueType.REFERENCE)
            setData(resourceId)
        }
    }

    private fun ResXmlElement.androidName(): String? =
        searchAttributeByResourceId(AndroidAttr.NAME)?.valueAsString

    companion object {
        const val PATCH_FORMAT_VERSION = "1"

        const val META_CARPORT_PATCHED = "dev.hadapkar.carport.patched"
        const val META_DISTRACTION_OPTIMIZED = "distractionOptimized"
        const val META_MAX_ASPECT = "android.max_aspect"
        const val META_MIN_CAR_API_LEVEL = "androidx.car.app.minCarApiLevel"
        const val META_GMS_CAR_APPLICATION = "com.google.android.gms.car.application"

        const val CATEGORY_CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER"
        val CAR_CATEGORIES = listOf(
            CATEGORY_CAR_LAUNCHER,
            "androidx.car.app.category.NAVIGATION",
            "android.intent.category.APP_MAPS",
        )

        private const val MIN_CAR_API_LEVEL = 7
        private const val APPLICATION_MAX_ASPECT = 4.0f
        private const val ACTIVITY_MAX_ASPECT = 5.0f

        private const val ACTION_MAIN = "android.intent.action.MAIN"
        private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

        private const val TAG_ACTIVITY = "activity"
        private const val TAG_ACTIVITY_ALIAS = "activity-alias"
        private const val TAG_INTENT_FILTER = "intent-filter"
        private const val TAG_ACTION = "action"
        private const val TAG_CATEGORY = "category"
        private const val TAG_META_DATA = "meta-data"

        private const val ATTR_NAME = "name"
        private const val ATTR_VALUE = "value"
        private const val ATTR_RESOURCE = "resource"
        private const val ATTR_RESIZEABLE_ACTIVITY = "resizeableActivity"
        private const val ATTR_CONFIG_CHANGES = "configChanges"
        private const val ATTR_APP_CATEGORY = "appCategory"

        val CAR_PERMISSIONS = listOf("androidx.car.app.ACCESS_SURFACE", "androidx.car.app.MAP_TEMPLATES")

        const val META_ORIGINAL_PREFIX = "dev.hadapkar.carport.original."
        private const val ORIGINAL_ABSENT = "absent"
        private val TRACKED_ORIENTATION = Tracked("screenOrientation", AndroidAttr.SCREEN_ORIENTATION)
        private val TRACKED_RESIZEABLE = Tracked(ATTR_RESIZEABLE_ACTIVITY, AndroidAttr.RESIZEABLE_ACTIVITY)
        private val TRACKED_CONFIG_CHANGES = Tracked(ATTR_CONFIG_CHANGES, AndroidAttr.CONFIG_CHANGES)
        private val TRACKED_APP_CATEGORY = Tracked(ATTR_APP_CATEGORY, AndroidAttr.APP_CATEGORY)
        private val TRACKED = listOf(TRACKED_ORIENTATION, TRACKED_RESIZEABLE, TRACKED_CONFIG_CHANGES, TRACKED_APP_CATEGORY)
    }
}

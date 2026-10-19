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

        val launcherFilters = findLauncherFilters()
        if (launcherFilters.isEmpty()) {
            throw PatchException("This app has no launcher activity, so there is nothing to open on the car screen.")
        }

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
        if (options.forceResizeable) {
            setResizeable(application)
        }

        for (filter in launcherFilters) {
            CAR_CATEGORIES.forEach { ensureCategory(filter, it) }
        }
        changes += "Added the car launcher entry to ${launcherFilters.size} launcher intent filter(s)"

        val activities = application.listElements(TAG_ACTIVITY)
        for (activity in activities) {
            setMetaBoolean(activity, META_DISTRACTION_OPTIMIZED, true)
            setMetaFloat(activity, META_MAX_ASPECT, ACTIVITY_MAX_ASPECT)
            if (options.forceResizeable) {
                setResizeable(activity)
            }
            if (options.unlockOrientation) {
                activity.removeAttributesWithId(AndroidAttr.SCREEN_ORIENTATION)
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
    }

    /** Intent filters (on activities or aliases) that declare action MAIN + category LAUNCHER. */
    private fun findLauncherFilters(): List<ResXmlElement> {
        val application = manifest.applicationElement ?: return emptyList()
        val components = application.listElements(TAG_ACTIVITY) + application.listElements(TAG_ACTIVITY_ALIAS)
        return components.flatMap { component ->
            component.listElements(TAG_INTENT_FILTER).filter { filter ->
                filter.listElements(TAG_ACTION).any { it.androidName() == ACTION_MAIN } &&
                    filter.listElements(TAG_CATEGORY).any { it.androidName() == CATEGORY_LAUNCHER }
            }
        }
    }

    private fun ensureCategory(filter: ResXmlElement, name: String) {
        if (filter.listElements(TAG_CATEGORY).any { it.androidName() == name }) return
        filter.createChildElement(TAG_CATEGORY)
            .getOrCreateAndroidAttribute(ATTR_NAME, AndroidAttr.NAME)
            .setValueAsString(name)
    }

    private fun setResizeable(element: ResXmlElement) {
        element.getOrCreateAndroidAttribute(ATTR_RESIZEABLE_ACTIVITY, AndroidAttr.RESIZEABLE_ACTIVITY)
            .setValueAsBoolean(true)
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
    }
}

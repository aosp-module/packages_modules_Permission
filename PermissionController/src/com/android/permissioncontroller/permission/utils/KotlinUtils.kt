/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.permissioncontroller.permission.utils

import android.Manifest
import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BACKUP
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission_group.NOTIFICATIONS
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.MODE_FOREGROUND
import android.app.AppOpsManager.MODE_IGNORED
import android.app.AppOpsManager.OPSTR_AUTO_REVOKE_PERMISSIONS_IF_UNUSED
import android.app.AppOpsManager.permissionToOp
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_MAIN
import android.content.Intent.CATEGORY_INFO
import android.content.Intent.CATEGORY_LAUNCHER
import android.content.pm.PackageManager
import android.content.pm.PackageManager.FLAG_PERMISSION_AUTO_REVOKED
import android.content.pm.PackageManager.FLAG_PERMISSION_ONE_TIME
import android.content.pm.PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
import android.content.pm.PackageManager.FLAG_PERMISSION_REVOKED_COMPAT
import android.content.pm.PackageManager.FLAG_PERMISSION_USER_FIXED
import android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET
import android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE
import android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.permission.PermissionManager
import android.provider.DeviceConfig
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.navigation.NavController
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.android.modules.utils.build.SdkLevel
import com.android.permissioncontroller.R
import com.android.permissioncontroller.permission.data.LightAppPermGroupLiveData
import com.android.permissioncontroller.permission.data.LightPackageInfoLiveData
import com.android.permissioncontroller.permission.data.get
import com.android.permissioncontroller.permission.model.livedatatypes.LightAppPermGroup
import com.android.permissioncontroller.permission.model.livedatatypes.LightPackageInfo
import com.android.permissioncontroller.permission.model.livedatatypes.LightPermission
import com.android.permissioncontroller.permission.model.livedatatypes.PermState
import com.android.permissioncontroller.permission.service.LocationAccessCheck
import com.android.permissioncontroller.permission.ui.handheld.SettingsWithLargeHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
/**
 * A set of util functions designed to work with kotlin, though they can work with java, as well.
 */
object KotlinUtils {

    private const val LOG_TAG = "PermissionController Utils"

    private const val PERMISSION_CONTROLLER_CHANGED_FLAG_MASK = FLAG_PERMISSION_USER_SET or
        FLAG_PERMISSION_USER_FIXED or
        FLAG_PERMISSION_ONE_TIME or
        FLAG_PERMISSION_REVOKED_COMPAT or
        FLAG_PERMISSION_ONE_TIME or
        FLAG_PERMISSION_REVIEW_REQUIRED or
        FLAG_PERMISSION_AUTO_REVOKED

    private const val KILL_REASON_APP_OP_CHANGE = "Permission related app op changed"
    private const val SAFETY_PROTECTION_RESOURCES_ENABLED = "safety_protection_enabled"

    /**
     * Importance level to define the threshold for whether a package is in a state which resets the
     * timer on its one-time permission session
     */
    private val ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_RESET_TIMER =
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

    /**
     * Importance level to define the threshold for whether a package is in a state which keeps its
     * one-time permission session alive after the timer ends
     */
    private val ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_KEEP_SESSION_ALIVE =
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

    /**
     * Given a Map, and a List, determines which elements are in the list, but not the map, and
     * vice versa. Used primarily for determining which liveDatas are already being watched, and
     * which need to be removed or added
     *
     * @param oldValues A map of key type K, with any value type
     * @param newValues A list of type K
     *
     * @return A pair, where the first value is all items in the list, but not the map, and the
     * second is all keys in the map, but not the list
     */
    fun <K> getMapAndListDifferences(
        newValues: Collection<K>,
        oldValues: Map<K, *>
    ): Pair<Set<K>, Set<K>> {
        val mapHas = oldValues.keys.toMutableSet()
        val listHas = newValues.toMutableSet()
        for (newVal in newValues) {
            if (oldValues.containsKey(newVal)) {
                mapHas.remove(newVal)
                listHas.remove(newVal)
            }
        }
        return listHas to mapHas
    }

    /**
     * Sort a given PreferenceGroup by the given comparison function.
     *
     * @param compare The function comparing two preferences, which will be used to sort
     * @param hasHeader Whether the group contains a LargeHeaderPreference, which will be kept at
     * the top of the list
     */
    fun sortPreferenceGroup(
        group: PreferenceGroup,
        compare: (lhs: Preference, rhs: Preference) -> Int,
        hasHeader: Boolean
    ) {
        val preferences = mutableListOf<Preference>()
        for (i in 0 until group.preferenceCount) {
            preferences.add(group.getPreference(i))
        }

        if (hasHeader) {
            preferences.sortWith(Comparator { lhs, rhs ->
                if (lhs is SettingsWithLargeHeader.LargeHeaderPreference) {
                    -1
                } else if (rhs is SettingsWithLargeHeader.LargeHeaderPreference) {
                    1
                } else {
                    compare(lhs, rhs)
                }
            })
        } else {
            preferences.sortWith(Comparator(compare))
        }

        for (i in 0 until preferences.size) {
            preferences[i].order = i
        }
    }

    /**
     * Gets a permission group's icon from the system.
     *
     * @param context The context from which to get the icon
     * @param groupName The name of the permission group whose icon we want
     *
     * @return The permission group's icon, the ic_perm_device_info icon if the group has no icon,
     * or the group does not exist
     */
    @JvmOverloads
    fun getPermGroupIcon(context: Context, groupName: String, tint: Int? = null): Drawable? {
        val groupInfo = Utils.getGroupInfo(groupName, context)
        var icon: Drawable? = null
        if (groupInfo != null && groupInfo.icon != 0) {
            icon = Utils.loadDrawable(context.packageManager, groupInfo.packageName,
                groupInfo.icon)
        }

        if (icon == null) {
            icon = context.getDrawable(R.drawable.ic_perm_device_info)
        }

        if (tint == null) {
            return Utils.applyTint(context, icon, android.R.attr.colorControlNormal)
        }

        icon?.setTint(tint)
        return icon
    }

    /**
     * Gets a permission group's label from the system.
     *
     * @param context The context from which to get the label
     * @param groupName The name of the permission group whose label we want
     *
     * @return The permission group's label, or the group name, if the group is invalid
     */
    fun getPermGroupLabel(context: Context, groupName: String): CharSequence {
        val groupInfo = Utils.getGroupInfo(groupName, context) ?: return groupName
        return groupInfo.loadSafeLabel(context.packageManager, 0f,
            TextUtils.SAFE_STRING_FLAG_FIRST_LINE or TextUtils.SAFE_STRING_FLAG_TRIM)
    }

    /**
     * Gets a permission group's description from the system.
     *
     * @param context The context from which to get the description
     * @param groupName The name of the permission group whose description we want
     *
     * @return The permission group's description, or an empty string, if the group is invalid, or
     * its description does not exist
     */
    fun getPermGroupDescription(context: Context, groupName: String): CharSequence {
        val groupInfo = Utils.getGroupInfo(groupName, context)
        var description: CharSequence = ""

        if (groupInfo is PermissionGroupInfo) {
            description = groupInfo.loadDescription(context.packageManager) ?: groupName
        } else if (groupInfo is PermissionInfo) {
            description = groupInfo.loadDescription(context.packageManager) ?: groupName
        }
        return description
    }

    /**
     * Gets a permission's label from the system.
     * @param context The context from which to get the label
     * @param permName The name of the permission whose label we want
     *
     * @return The permission's label, or the permission name, if the permission is invalid
     */
    fun getPermInfoLabel(context: Context, permName: String): CharSequence {
        return try {
            context.packageManager.getPermissionInfo(permName, 0).loadSafeLabel(
                context.packageManager, 20000.toFloat(), TextUtils.SAFE_STRING_FLAG_TRIM)
        } catch (e: PackageManager.NameNotFoundException) {
            permName
        }
    }

    /**
     * Gets a permission's icon from the system.
     * @param context The context from which to get the icon
     * @param permName The name of the permission whose icon we want
     *
     * @return The permission's icon, or the permission's group icon if the icon isn't set, or
     * the ic_perm_device_info icon if the permission is invalid.
     */
    fun getPermInfoIcon(context: Context, permName: String): Drawable? {
        return try {
            val permInfo = context.packageManager.getPermissionInfo(permName, 0)
            var icon: Drawable? = null
            if (permInfo.icon != 0) {
                icon = Utils.applyTint(context, permInfo.loadUnbadgedIcon(context.packageManager),
                    android.R.attr.colorControlNormal)
            }

            if (icon == null) {
                val groupName = PermissionMapping.getGroupOfPermission(permInfo) ?: permInfo.name
                icon = getPermGroupIcon(context, groupName)
            }

            icon
        } catch (e: PackageManager.NameNotFoundException) {
            Utils.applyTint(context, context.getDrawable(R.drawable.ic_perm_device_info),
                android.R.attr.colorControlNormal)
        }
    }

    /**
     * Gets a permission's description from the system.
     *
     * @param context The context from which to get the description
     * @param permName The name of the permission whose description we want
     *
     * @return The permission's description, or an empty string, if the group is invalid, or
     * its description does not exist
     */
    fun getPermInfoDescription(context: Context, permName: String): CharSequence {
        return try {
            val permInfo = context.packageManager.getPermissionInfo(permName, 0)
            permInfo.loadDescription(context.packageManager) ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }

    /**
     * Gets a package's badged icon from the system.
     *
     * @param app The current application
     * @param packageName The name of the package whose icon we want
     * @param user The user for whom we want the package icon
     *
     * @return The package's icon, or null, if the package does not exist
     */
    @JvmOverloads
    fun getBadgedPackageIcon(
        app: Application,
        packageName: String,
        user: UserHandle
    ): Drawable? {
        return try {
            val userContext = Utils.getUserContext(app, user)
            val appInfo = userContext.packageManager.getApplicationInfo(packageName, 0)
            Utils.getBadgedIcon(app, appInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Gets a package's badged label from the system.
     *
     * @param app The current application
     * @param packageName The name of the package whose label we want
     * @param user The user for whom we want the package label
     *
     * @return The package's label
     */
    fun getPackageLabel(app: Application, packageName: String, user: UserHandle): String {
        return try {
            val userContext = Utils.getUserContext(app, user)
            val appInfo = userContext.packageManager.getApplicationInfo(packageName, 0)
            Utils.getFullAppLabel(appInfo, app)
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun convertToBitmap(pkgIcon: Drawable): Bitmap {
        val pkgIconBmp = Bitmap.createBitmap(pkgIcon.intrinsicWidth, pkgIcon.intrinsicHeight,
            Bitmap.Config.ARGB_8888)
        // Draw the icon so it can be displayed.
        val canvas = Canvas(pkgIconBmp)
        pkgIcon.setBounds(0, 0, pkgIcon.intrinsicWidth, pkgIcon.intrinsicHeight)
        pkgIcon.draw(canvas)
        return pkgIconBmp
    }

    /**
     * Gets a package's uid, using a cached liveData value, if the liveData is currently being
     * observed (and thus has an up-to-date value).
     *
     * @param app The current application
     * @param packageName The name of the package whose uid we want
     * @param user The user we want the package uid for
     *
     * @return The package's UID, or null if the package or user is invalid
     */
    fun getPackageUid(app: Application, packageName: String, user: UserHandle): Int? {
        val liveData = LightPackageInfoLiveData[packageName, user]
        val liveDataUid = liveData.value?.uid
        return if (liveDataUid != null && liveData.hasActiveObservers()) liveDataUid else {
            val userContext = Utils.getUserContext(app, user)
            try {
                val appInfo = userContext.packageManager.getApplicationInfo(packageName, 0)
                appInfo.uid
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    /**
     * Determines if an app is R or above, or if it is Q-, and has auto revoke enabled
     *
     * @param app The currenct application
     * @param packageName The package name to check
     * @param user The user whose package we want to check
     *
     * @return true if the package is R+ (and not a work profile) or has auto revoke enabled
     */
    fun isROrAutoRevokeEnabled(app: Application, packageName: String, user: UserHandle): Boolean {
        val userContext = Utils.getUserContext(app, user)
        val liveDataValue = LightPackageInfoLiveData[packageName, user].value
        val (targetSdk, uid) = if (liveDataValue != null) {
            liveDataValue.targetSdkVersion to liveDataValue.uid
        } else {
            val appInfo = userContext.packageManager.getApplicationInfo(packageName, 0)
            appInfo.targetSdkVersion to appInfo.uid
        }

        if (targetSdk <= Build.VERSION_CODES.Q) {
            val opsManager = app.getSystemService(AppOpsManager::class.java)!!
            return opsManager.unsafeCheckOpNoThrow(OPSTR_AUTO_REVOKE_PERMISSIONS_IF_UNUSED, uid,
                packageName) == MODE_ALLOWED
        }
        return true
    }

    /**
     * Determine if the given permission should be treated as split from a
     * non-runtime permission for an application targeting the given SDK level.
     */
    private fun isPermissionSplitFromNonRuntime(
        app: Application,
        permName: String,
        targetSdk: Int
    ): Boolean {
        val permissionManager = app.getSystemService(PermissionManager::class.java) ?: return false
        val splitPerms = permissionManager.splitPermissions
        val size = splitPerms.size
        for (i in 0 until size) {
            val splitPerm = splitPerms[i]
            if (targetSdk < splitPerm.targetSdk && splitPerm.newPermissions.contains(permName)) {
                val perm = app.packageManager.getPermissionInfo(splitPerm.splitPermission, 0)
                return perm != null && perm.protection != PermissionInfo.PROTECTION_DANGEROUS
            }
        }
        return false
    }

    /**
     * Set a list of flags for a set of permissions of a LightAppPermGroup
     *
     * @param app: The current application
     * @param group: The LightAppPermGroup whose permission flags we wish to set
     * @param flags: Pairs of <FlagInt, ShouldSetFlag>
     * @param filterPermissions: A list of permissions to filter by. Only the filtered permissions
     * will be set
     *
     * @return A new LightAppPermGroup with the flags set.
     */
    fun setGroupFlags(
        app: Application,
        group: LightAppPermGroup,
        vararg flags: Pair<Int, Boolean>,
        filterPermissions: List<String> = group.permissions.keys.toList()
    ): LightAppPermGroup {
        var flagMask = 0
        var flagsToSet = 0
        for ((flag, shouldSet) in flags) {
            flagMask = flagMask or flag
            if (shouldSet) {
                flagsToSet = flagsToSet or flag
            }
        }

        val newPerms = mutableMapOf<String, LightPermission>()
        for ((permName, perm) in group.permissions) {
            if (permName !in filterPermissions) {
                continue
            }
            // Check if flags need to be updated
            if (flagMask and (perm.flags xor flagsToSet) != 0) {
                app.packageManager.updatePermissionFlags(permName, group.packageName,
                    group.userHandle, *flags)
            }
            newPerms[permName] = LightPermission(group.packageInfo, perm.permInfo,
                perm.isGrantedIncludingAppOp, perm.flags or flagsToSet, perm.foregroundPerms)
        }
        return LightAppPermGroup(group.packageInfo, group.permGroupInfo, newPerms,
            group.hasInstallToRuntimeSplit, group.specialLocationGrant)
    }

    /**
     * Grant all foreground runtime permissions of a LightAppPermGroup
     *
     * <p>This also automatically grants all app ops for permissions that have app ops.
     *
     * @param app The current application
     * @param group The group whose permissions should be granted
     * @param filterPermissions If not specified, all permissions of the group will be granted.
     *                          Otherwise only permissions in {@code filterPermissions} will be
     *                          granted.
     *
     * @return a new LightAppPermGroup, reflecting the new state
     */
    @JvmOverloads
    fun grantForegroundRuntimePermissions(
        app: Application,
        group: LightAppPermGroup,
        filterPermissions: List<String> = group.permissions.keys.toList(),
        isOneTime: Boolean = false
    ): LightAppPermGroup {
        return grantRuntimePermissions(app, group, false, isOneTime, filterPermissions)
    }

    /**
     * Grant all background runtime permissions of a LightAppPermGroup
     *
     * <p>This also automatically grants all app ops for permissions that have app ops.
     *
     * @param app The current application
     * @param group The group whose permissions should be granted
     * @param filterPermissions If not specified, all permissions of the group will be granted.
     *                          Otherwise only permissions in {@code filterPermissions} will be
     *                          granted.
     *
     * @return a new LightAppPermGroup, reflecting the new state
     */
    @JvmOverloads
    fun grantBackgroundRuntimePermissions(
        app: Application,
        group: LightAppPermGroup,
        filterPermissions: List<String> = group.permissions.keys.toList()
    ): LightAppPermGroup {
        return grantRuntimePermissions(app, group, true, false, filterPermissions)
    }

    private fun grantRuntimePermissions(
        app: Application,
        group: LightAppPermGroup,
        grantBackground: Boolean,
        isOneTime: Boolean = false,
        filterPermissions: List<String> = group.permissions.keys.toList()
    ): LightAppPermGroup {
        val wasOneTime = group.isOneTime
        val newPerms = group.permissions.toMutableMap()
        var shouldKillForAnyPermission = false
        for (permName in filterPermissions) {
            val perm = group.permissions[permName] ?: continue
            val isBackgroundPerm = permName in group.backgroundPermNames
            if (isBackgroundPerm == grantBackground) {
                val (newPerm, shouldKill) = grantRuntimePermission(app, perm, isOneTime, group)
                newPerms[newPerm.name] = newPerm
                shouldKillForAnyPermission = shouldKillForAnyPermission || shouldKill
            }
        }
        if (!newPerms.isEmpty()) {
            val user = UserHandle.getUserHandleForUid(group.packageInfo.uid)
            for (groupPerm in group.allPermissions.values) {
                var permFlags = groupPerm!!.flags
                permFlags = permFlags.clearFlag(PackageManager.FLAG_PERMISSION_AUTO_REVOKED)
                if (groupPerm!!.flags != permFlags) {
                    app.packageManager.updatePermissionFlags(groupPerm!!.name,
                        group.packageInfo.packageName, PERMISSION_CONTROLLER_CHANGED_FLAG_MASK,
                        permFlags, user)
                }
            }
        }

        if (shouldKillForAnyPermission) {
            (app.getSystemService(ActivityManager::class.java) as ActivityManager).killUid(
                group.packageInfo.uid, KILL_REASON_APP_OP_CHANGE)
        }
        val newGroup = LightAppPermGroup(group.packageInfo, group.permGroupInfo, newPerms,
            group.hasInstallToRuntimeSplit, group.specialLocationGrant)
        // If any permission in the group is one time granted, start one time permission session.
        if (newGroup.permissions.any { it.value.isOneTime && it.value.isGrantedIncludingAppOp }) {
            if (SdkLevel.isAtLeastT()) {
                app.getSystemService(PermissionManager::class.java)!!.startOneTimePermissionSession(
                        group.packageName, Utils.getOneTimePermissionsTimeout(),
                        Utils.getOneTimePermissionsKilledDelay(false),
                        ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_RESET_TIMER,
                        ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_KEEP_SESSION_ALIVE)
            } else {
                app.getSystemService(PermissionManager::class.java)!!.startOneTimePermissionSession(
                        group.packageName, Utils.getOneTimePermissionsTimeout(),
                        ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_RESET_TIMER,
                        ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_KEEP_SESSION_ALIVE)
            }
        }
        return newGroup
    }

    /**
     * Grants a single runtime permission
     *
     * @param app The current application
     * @param perm The permission which should be granted.
     * @param group An optional app permission group in which to look for background or foreground
     * permissions
     *
     * @return a LightPermission and boolean pair <permission with updated state (or the original
     * state, if it wasn't changed), should kill app>
     */
    private fun grantRuntimePermission(
        app: Application,
        perm: LightPermission,
        isOneTime: Boolean,
        group: LightAppPermGroup
    ): Pair<LightPermission, Boolean> {
        val pkgInfo = group.packageInfo
        val user = UserHandle.getUserHandleForUid(pkgInfo.uid)
        val supportsRuntime = pkgInfo.targetSdkVersion >= Build.VERSION_CODES.M
        val isGrantingAllowed = (!pkgInfo.isInstantApp || perm.isInstantPerm) &&
            (supportsRuntime || !perm.isRuntimeOnly)
        // Do not touch permissions fixed by the system, or permissions that cannot be granted
        if (!isGrantingAllowed || perm.isSystemFixed) {
            return perm to false
        }

        var newFlags = perm.flags
        var isGranted = perm.isGrantedIncludingAppOp
        var shouldKill = false

        // Grant the permission if needed.
        if (!perm.isGrantedIncludingAppOp) {
            val affectsAppOp = permissionToOp(perm.name) != null || perm.isBackgroundPermission

            // TODO 195016052: investigate adding split permission handling
            if (supportsRuntime) {
                app.packageManager.grantRuntimePermission(group.packageName, perm.name, user)
                isGranted = true
            } else if (affectsAppOp) {
                // Legacy apps do not know that they have to retry access to a
                // resource due to changes in runtime permissions (app ops in this
                // case). Therefore, we restart them on app op change, so they
                // can pick up the change.
                shouldKill = true
                isGranted = true
            }
            newFlags = newFlags.clearFlag(PackageManager.FLAG_PERMISSION_REVOKED_COMPAT)
            newFlags = newFlags.clearFlag(PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED)

            // If this permission affects an app op, ensure the permission app op is enabled
            // before the permission grant.
            if (affectsAppOp) {
                allowAppOp(app, perm, group)
            }
        }

        // Granting a permission explicitly means the user already
        // reviewed it so clear the review flag on every grant.
        newFlags = newFlags.clearFlag(PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED)

        // Update the permission flags
        // Now the apps can ask for the permission as the user
        // no longer has it fixed in a denied state.
        newFlags = newFlags.clearFlag(PackageManager.FLAG_PERMISSION_USER_FIXED)
        newFlags = newFlags.setFlag(PackageManager.FLAG_PERMISSION_USER_SET)
        newFlags = newFlags.clearFlag(PackageManager.FLAG_PERMISSION_AUTO_REVOKED)

        newFlags = if (isOneTime) {
            newFlags.setFlag(PackageManager.FLAG_PERMISSION_ONE_TIME)
        } else {
            newFlags.clearFlag(PackageManager.FLAG_PERMISSION_ONE_TIME)
        }

        // If we newly grant background access to the fine location, double-guess the user some
        // time later if this was really the right choice.
        if (!perm.isGrantedIncludingAppOp && isGranted) {
            var triggerLocationAccessCheck = false
            if (perm.name == ACCESS_FINE_LOCATION) {
                val bgPerm = group.permissions[perm.backgroundPermission]
                triggerLocationAccessCheck = bgPerm?.isGrantedIncludingAppOp == true
            } else if (perm.name == ACCESS_BACKGROUND_LOCATION) {
                val fgPerm = group.permissions[ACCESS_FINE_LOCATION]
                triggerLocationAccessCheck = fgPerm?.isGrantedIncludingAppOp == true
            }
            if (triggerLocationAccessCheck) {
                // trigger location access check
                LocationAccessCheck(app, null).checkLocationAccessSoon()
            }
        }

        if (perm.flags != newFlags) {
            app.packageManager.updatePermissionFlags(perm.name, group.packageInfo.packageName,
                PERMISSION_CONTROLLER_CHANGED_FLAG_MASK, newFlags, user)
        }

        val newState = PermState(newFlags, isGranted)
        return LightPermission(perm.pkgInfo, perm.permInfo, newState,
            perm.foregroundPerms) to shouldKill
    }

    /**
     * Revoke all foreground runtime permissions of a LightAppPermGroup
     *
     * <p>This also disallows all app ops for permissions that have app ops.
     *
     * @param app The current application
     * @param group The group whose permissions should be revoked
     * @param userFixed If the user requested that they do not want to be asked again
     * @param oneTime If the permission should be mark as one-time
     * @param filterPermissions If not specified, all permissions of the group will be revoked.
     *                          Otherwise only permissions in {@code filterPermissions} will be
     *                          revoked.
     *
     * @return a LightAppPermGroup representing the new state
     */
    @JvmOverloads
    fun revokeForegroundRuntimePermissions(
        app: Application,
        group: LightAppPermGroup,
        userFixed: Boolean = false,
        oneTime: Boolean = false,
        filterPermissions: List<String> = group.permissions.keys.toList()
    ): LightAppPermGroup {
        return revokeRuntimePermissions(app, group, false, userFixed, oneTime, filterPermissions)
    }

    /**
     * Revoke all background runtime permissions of a LightAppPermGroup
     *
     * <p>This also disallows all app ops for permissions that have app ops.
     *
     * @param app The current application
     * @param group The group whose permissions should be revoked
     * @param userFixed If the user requested that they do not want to be asked again
     * @param filterPermissions If not specified, all permissions of the group will be revoked.
     *                          Otherwise only permissions in {@code filterPermissions} will be
     *                          revoked.
     *
     * @return a LightAppPermGroup representing the new state
     */
    @JvmOverloads
    fun revokeBackgroundRuntimePermissions(
        app: Application,
        group: LightAppPermGroup,
        userFixed: Boolean = false,
        oneTime: Boolean = false,
        filterPermissions: List<String> = group.permissions.keys.toList()
    ): LightAppPermGroup {
        return revokeRuntimePermissions(app, group, true, userFixed, oneTime, filterPermissions)
    }

    private fun revokeRuntimePermissions(
        app: Application,
        group: LightAppPermGroup,
        revokeBackground: Boolean,
        userFixed: Boolean,
        oneTime: Boolean,
        filterPermissions: List<String>
    ): LightAppPermGroup {
        val wasOneTime = group.isOneTime
        val newPerms = group.permissions.toMutableMap()
        var shouldKillForAnyPermission = false
        for (permName in filterPermissions) {
            val perm = group.permissions[permName] ?: continue
            val isBackgroundPerm = permName in group.backgroundPermNames
            if (isBackgroundPerm == revokeBackground) {
                val (newPerm, shouldKill) =
                    revokeRuntimePermission(app, perm, userFixed, oneTime, group)
                newPerms[newPerm.name] = newPerm
                shouldKillForAnyPermission = shouldKillForAnyPermission || shouldKill
            }
        }

        if (shouldKillForAnyPermission && !shouldSkipKillForGroup(app, group)) {
            (app.getSystemService(ActivityManager::class.java) as ActivityManager).killUid(
                group.packageInfo.uid, KILL_REASON_APP_OP_CHANGE)
        }

        val newGroup = LightAppPermGroup(group.packageInfo, group.permGroupInfo, newPerms,
            group.hasInstallToRuntimeSplit, group.specialLocationGrant)

        if (wasOneTime && !anyPermsOfPackageOneTimeGranted(app, newGroup.packageInfo, newGroup)) {
            app.getSystemService(PermissionManager::class.java)!!.stopOneTimePermissionSession(
                group.packageName)
        }
        return newGroup
    }

    /**
     * Revoke background permissions
     *
     * @param context context
     * @param packageName Name of the package
     * @param permissionGroupName Name of the permission group
     * @param user User handle
     * @param postRevokeHandler Optional callback that lets us perform an action on revoke
     */
    fun revokeBackgroundRuntimePermissions(
        context: Context,
        packageName: String,
        permissionGroupName: String,
        user: UserHandle,
        postRevokeHandler: Runnable?
    ) {
        GlobalScope.launch(Dispatchers.Main) {
            val group = LightAppPermGroupLiveData[packageName, permissionGroupName, user]
                .getInitializedValue()
            if (group != null) {
                revokeBackgroundRuntimePermissions(context.application, group)
            }
            if (postRevokeHandler != null) {
                postRevokeHandler.run()
            }
        }
    }

    /**
     * Determines if any permissions of a package are granted for one-time only
     *
     * @param app The current application
     * @param packageInfo The packageInfo we wish to examine
     * @param group Optional, the current app permission group we are examining
     *
     * @return true if any permission in the package is granted for one time, false otherwise
     */
    private fun anyPermsOfPackageOneTimeGranted(
        app: Application,
        packageInfo: LightPackageInfo,
        group: LightAppPermGroup? = null
    ): Boolean {
        val user = group?.userHandle ?: UserHandle.getUserHandleForUid(packageInfo.uid)
        if (group?.isOneTime == true) {
            return true
        }
        for ((idx, permName) in packageInfo.requestedPermissions.withIndex()) {
            if (permName in group?.permissions ?: emptyMap()) {
                continue
            }
            val flags = app.packageManager.getPermissionFlags(permName, packageInfo.packageName,
                user) and FLAG_PERMISSION_ONE_TIME
            val granted = packageInfo.requestedPermissionsFlags[idx] ==
                PackageManager.PERMISSION_GRANTED &&
                (flags and FLAG_PERMISSION_REVOKED_COMPAT) == 0
            if (granted && (flags and FLAG_PERMISSION_ONE_TIME) != 0) {
                return true
            }
        }
        return false
    }
    /**
     * Revokes a single runtime permission.
     *
     * @param app The current application
     * @param perm The permission which should be revoked.
     * @param userFixed If the user requested that they do not want to be asked again
     * @param group An optional app permission group in which to look for background or foreground
     * permissions
     *
     * @return a LightPermission and boolean pair <permission with updated state (or the original
     * state, if it wasn't changed), should kill app>
     */
    private fun revokeRuntimePermission(
        app: Application,
        perm: LightPermission,
        userFixed: Boolean,
        oneTime: Boolean,
        group: LightAppPermGroup
    ): Pair<LightPermission, Boolean> {
        // Do not touch permissions fixed by the system.
        if (perm.isSystemFixed) {
            return perm to false
        }

        val user = UserHandle.getUserHandleForUid(group.packageInfo.uid)
        var newFlags = perm.flags
        var isGranted = perm.isGrantedIncludingAppOp
        val supportsRuntime = group.packageInfo.targetSdkVersion >= Build.VERSION_CODES.M
        var shouldKill = false

        val affectsAppOp = permissionToOp(perm.name) != null || perm.isBackgroundPermission

        if (perm.isGrantedIncludingAppOp) {
            if (supportsRuntime && !isPermissionSplitFromNonRuntime(app, perm.name,
                            group.packageInfo.targetSdkVersion)) {
                // Revoke the permission if needed.
                app.packageManager.revokeRuntimePermission(group.packageInfo.packageName,
                    perm.name, user)
                isGranted = false
            } else if (affectsAppOp) {
                // If the permission has no corresponding app op, then it is a
                // third-party one and we do not offer toggling of such permissions.

                // Disabling an app op may put the app in a situation in which it
                // has a handle to state it shouldn't have, so we have to kill the
                // app. This matches the revoke runtime permission behavior.
                shouldKill = true
                newFlags = newFlags.setFlag(PackageManager.FLAG_PERMISSION_REVOKED_COMPAT)
                isGranted = false
            }

            newFlags = newFlags.clearFlag(PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED)
            if (affectsAppOp) {
                disallowAppOp(app, perm, group)
            }
        }

        // Update the permission flags.
        // Take a note that the user fixed the permission, if applicable.
        newFlags = if (userFixed) newFlags.setFlag(PackageManager.FLAG_PERMISSION_USER_FIXED)
        else newFlags.clearFlag(PackageManager.FLAG_PERMISSION_USER_FIXED)
        newFlags = if (oneTime) newFlags.clearFlag(PackageManager.FLAG_PERMISSION_USER_SET)
        else newFlags.setFlag(PackageManager.FLAG_PERMISSION_USER_SET)
        newFlags = if (oneTime) newFlags.setFlag(PackageManager.FLAG_PERMISSION_ONE_TIME)
        else newFlags.clearFlag(PackageManager.FLAG_PERMISSION_ONE_TIME)
        newFlags = newFlags.clearFlag(PackageManager.FLAG_PERMISSION_AUTO_REVOKED)
        newFlags = newFlags.clearFlag(PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED)

        if (perm.flags != newFlags) {
            app.packageManager.updatePermissionFlags(perm.name, group.packageInfo.packageName,
                PERMISSION_CONTROLLER_CHANGED_FLAG_MASK, newFlags, user)
        }

        // If we revoke background access to the fine location, we trigger a check to remove
        // notification warning about background location access
        if (perm.isGrantedIncludingAppOp && !isGranted) {
            var cancelLocationAccessWarning = false
            if (perm.name == ACCESS_FINE_LOCATION) {
                val bgPerm = group.permissions[perm.backgroundPermission]
                cancelLocationAccessWarning = bgPerm?.isGrantedIncludingAppOp == true
            } else if (perm.name == ACCESS_BACKGROUND_LOCATION) {
                val fgPerm = group.permissions[ACCESS_FINE_LOCATION]
                cancelLocationAccessWarning = fgPerm?.isGrantedIncludingAppOp == true
            }
            if (cancelLocationAccessWarning) {
                // cancel location access warning notification
                LocationAccessCheck(app, null).cancelBackgroundAccessWarningNotification(
                    group.packageInfo.packageName,
                    user,
                    true
                )
            }
        }

        val newState = PermState(newFlags, isGranted)
        return LightPermission(perm.pkgInfo, perm.permInfo, newState,
            perm.foregroundPerms) to shouldKill
    }

    private fun Int.setFlag(flagToSet: Int): Int {
        return this or flagToSet
    }

    private fun Int.clearFlag(flagToSet: Int): Int {
        return this and flagToSet.inv()
    }

    /**
     * Allow the app op for a permission/uid.
     *
     * <p>There are three cases:
     * <dl>
     * <dt>The permission is not split into foreground/background</dt>
     * <dd>The app op matching the permission will be set to {@link AppOpsManager#MODE_ALLOWED}</dd>
     * <dt>The permission is a foreground permission:</dt>
     * <dd><dl><dt>The background permission permission is granted</dt>
     * <dd>The app op matching the permission will be set to {@link AppOpsManager#MODE_ALLOWED}</dd>
     * <dt>The background permission permission is <u>not</u> granted</dt>
     * <dd>The app op matching the permission will be set to
     * {@link AppOpsManager#MODE_FOREGROUND}</dd>
     * </dl></dd>
     * <dt>The permission is a background permission:</dt>
     * <dd>All granted foreground permissions for this background permission will be set to
     * {@link AppOpsManager#MODE_ALLOWED}</dd>
     * </dl>
     *
     * @param app The current application
     * @param perm The LightPermission whose app op should be allowed
     * @param group The LightAppPermGroup which will be looked in for foreground or
     * background LightPermission objects
     *
     * @return {@code true} iff app-op was changed
     */
    private fun allowAppOp(
        app: Application,
        perm: LightPermission,
        group: LightAppPermGroup
    ): Boolean {
        val packageName = group.packageInfo.packageName
        val uid = group.packageInfo.uid
        val appOpsManager = app.getSystemService(AppOpsManager::class.java) as AppOpsManager
        var wasChanged = false

        if (perm.isBackgroundPermission && perm.foregroundPerms != null) {
            for (foregroundPermName in perm.foregroundPerms) {
                val fgPerm = group.permissions[foregroundPermName]
                val appOpName = permissionToOp(foregroundPermName) ?: continue

                if (fgPerm != null && fgPerm.isGrantedIncludingAppOp) {
                    wasChanged = wasChanged || setOpMode(appOpName, uid, packageName, MODE_ALLOWED,
                        appOpsManager)
                }
            }
        } else {
            val appOpName = permissionToOp(perm.name) ?: return false
            if (perm.backgroundPermission != null) {
                wasChanged = if (group.permissions.containsKey(perm.backgroundPermission)) {
                    val bgPerm = group.permissions[perm.backgroundPermission]
                    val mode = if (bgPerm != null && bgPerm.isGrantedIncludingAppOp) MODE_ALLOWED
                    else MODE_FOREGROUND

                    setOpMode(appOpName, uid, packageName, mode, appOpsManager)
                } else {
                    // The app requested a permission that has a background permission but it did
                    // not request the background permission, hence it can never get background
                    // access
                    setOpMode(appOpName, uid, packageName, MODE_FOREGROUND, appOpsManager)
                }
            } else {
                wasChanged = setOpMode(appOpName, uid, packageName, MODE_ALLOWED, appOpsManager)
            }
        }
        return wasChanged
    }

    /**
     * Disallow the app op for a permission/uid.
     *
     * <p>There are three cases:
     * <dl>
     * <dt>The permission is not split into foreground/background</dt>
     * <dd>The app op matching the permission will be set to {@link AppOpsManager#MODE_IGNORED}</dd>
     * <dt>The permission is a foreground permission:</dt>
     * <dd>The app op matching the permission will be set to {@link AppOpsManager#MODE_IGNORED}</dd>
     * <dt>The permission is a background permission:</dt>
     * <dd>All granted foreground permissions for this background permission will be set to
     * {@link AppOpsManager#MODE_FOREGROUND}</dd>
     * </dl>
     *
     * @param app The current application
     * @param perm The LightPermission whose app op should be allowed
     * @param group The LightAppPermGroup which will be looked in for foreground or
     * background LightPermission objects
     *
     * @return {@code true} iff app-op was changed
     */
    private fun disallowAppOp(
        app: Application,
        perm: LightPermission,
        group: LightAppPermGroup
    ): Boolean {
        val packageName = group.packageInfo.packageName
        val uid = group.packageInfo.uid
        val appOpsManager = app.getSystemService(AppOpsManager::class.java) as AppOpsManager
        var wasChanged = false

        if (perm.isBackgroundPermission && perm.foregroundPerms != null) {
            for (foregroundPermName in perm.foregroundPerms) {
                val fgPerm = group.permissions[foregroundPermName]
                if (fgPerm != null && fgPerm.isGrantedIncludingAppOp) {
                    val appOpName = permissionToOp(foregroundPermName) ?: return false
                    wasChanged = wasChanged || setOpMode(appOpName, uid, packageName,
                        MODE_FOREGROUND, appOpsManager)
                }
            }
        } else {
            val appOpName = permissionToOp(perm.name) ?: return false
            wasChanged = setOpMode(appOpName, uid, packageName, MODE_IGNORED, appOpsManager)
        }
        return wasChanged
    }

    /**
     * Set mode of an app-op if needed.
     *
     * @param op The op to set
     * @param uid The uid the app-op belongs to
     * @param packageName The package the app-op belongs to
     * @param mode The new mode
     * @param manager The app ops manager to use to change the app op
     *
     * @return {@code true} iff app-op was changed
     */
    private fun setOpMode(
        op: String,
        uid: Int,
        packageName: String,
        mode: Int,
        manager: AppOpsManager
    ): Boolean {
        val currentMode = manager.unsafeCheckOpRaw(op, uid, packageName)
        if (currentMode == mode) {
            return false
        }
        manager.setUidMode(op, uid, mode)
        return true
    }

    private fun shouldSkipKillForGroup(app: Application, group: LightAppPermGroup): Boolean {
        if (group.permGroupName != NOTIFICATIONS) {
            return false
        }

        return shouldSkipKillOnPermDeny(app, POST_NOTIFICATIONS, group.packageName,
            group.userHandle)
    }

    /**
     * Determine if the usual "kill app on permission denial" should be skipped. It should be
     * skipped if the permission is POST_NOTIFICATIONS, the app holds the BACKUP permission, and
     * a backup restore is currently in progress.
     *
     * @param app the current application
     * @param permission the permission being denied
     * @param packageName the package the permission was denied for
     * @param user the user whose package the permission was denied for
     *
     * @return true if the permission denied was POST_NOTIFICATIONS, the app is a backup app, and a
     * backup restore is in progress, false otherwise
     */
    fun shouldSkipKillOnPermDeny(
        app: Application,
        permission: String,
        packageName: String,
        user: UserHandle
    ): Boolean {
        val userContext: Context = Utils.getUserContext(app, user)
        if (permission != POST_NOTIFICATIONS || userContext.packageManager
            .checkPermission(BACKUP, packageName) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        return try {
            val isInSetup = Settings.Secure.getInt(userContext.contentResolver,
                Settings.Secure.USER_SETUP_COMPLETE, user.identifier) == 0
            val isInDeferredSetup = Settings.Secure.getInt(userContext.contentResolver,
                Settings.Secure.USER_SETUP_PERSONALIZATION_STATE, user.identifier) ==
                    Settings.Secure.USER_SETUP_PERSONALIZATION_STARTED
            isInSetup || isInDeferredSetup
        } catch (e: Settings.SettingNotFoundException) {
            Log.w(LOG_TAG, "Failed to check if the user is in restore: $e")
            false
        }
    }

    /**
     * Determine if a given package has a launch intent. Will function correctly even if called
     * before user is unlocked.
     *
     * @param context: The context from which to retrieve the package
     * @param packageName: The package name to check
     *
     * @return whether or not the given package has a launch intent
     */
    fun packageHasLaunchIntent(context: Context, packageName: String): Boolean {
        val intentToResolve = Intent(ACTION_MAIN)
        intentToResolve.addCategory(CATEGORY_INFO)
        intentToResolve.setPackage(packageName)
        var resolveInfos = context.packageManager.queryIntentActivities(intentToResolve,
            MATCH_DIRECT_BOOT_AWARE or MATCH_DIRECT_BOOT_UNAWARE)

        if (resolveInfos == null || resolveInfos.size <= 0) {
            intentToResolve.removeCategory(CATEGORY_INFO)
            intentToResolve.addCategory(CATEGORY_LAUNCHER)
            intentToResolve.setPackage(packageName)
            resolveInfos = context.packageManager.queryIntentActivities(intentToResolve,
                MATCH_DIRECT_BOOT_AWARE or MATCH_DIRECT_BOOT_UNAWARE)
        }
        return resolveInfos != null && resolveInfos.size > 0
    }

    /**
     * Set selected location accuracy flags for COARSE and FINE location permissions.
     *
     * @param app: The current application
     * @param group: The LightAppPermGroup whose permission flags we wish to set
     * @param isFineSelected: Whether fine location is selected
     */
    fun setFlagsWhenLocationAccuracyChanged(
        app: Application,
        group: LightAppPermGroup,
        isFineSelected: Boolean
    ) {
        if (isFineSelected) {
            setGroupFlags(app, group,
                PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY to true,
                filterPermissions = listOf(ACCESS_FINE_LOCATION))
            setGroupFlags(app, group,
                PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY to false,
                filterPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            setGroupFlags(app, group,
                PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY to false,
                filterPermissions = listOf(ACCESS_FINE_LOCATION))
            setGroupFlags(app, group,
                PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY to true,
                filterPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    /**
     * Determines whether we should show the safety protection resources.
     * We show the resources only if
     * (1) the build version is T or after and
     * (2) the feature flag safety_protection_enabled is enabled and
     * (3) the config value config_safetyProtectionEnabled is enabled/true and
     * (4) the resources exist (currently the resources only exist on GMS devices)
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun shouldShowSafetyProtectionResources(context: Context): Boolean {
        return SdkLevel.isAtLeastT() &&
            DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_PRIVACY, SAFETY_PROTECTION_RESOURCES_ENABLED, false) &&
            context.getResources().getBoolean(
                Resources.getSystem()
                    .getIdentifier("config_safetyProtectionEnabled", "bool", "android")) &&
            context.getDrawable(android.R.drawable.ic_safety_protection) != null &&
            !context.getString(android.R.string.safety_protection_display_text).isNullOrEmpty()
    }
}

/**
 * Get the [value][LiveData.getValue], suspending until [isInitialized] if not yet so
 */
suspend fun <T, LD : LiveData<T>> LD.getInitializedValue(
    observe: LD.(Observer<T>) -> Unit = { observeForever(it) },
    isInitialized: LD.() -> Boolean = { value != null }
): T {
    return if (isInitialized()) {
        value as T
    } else {
        suspendCoroutine { continuation: Continuation<T> ->
            val observer = AtomicReference<Observer<T>>()
            observer.set(Observer { newValue ->
                if (isInitialized()) {
                    GlobalScope.launch(Dispatchers.Main) {
                        observer.getAndSet(null)?.let { observerSnapshot ->
                            removeObserver(observerSnapshot)
                            continuation.resume(newValue)
                        }
                    }
                }
            })

            GlobalScope.launch(Dispatchers.Main) {
                observe(observer.get())
            }
        }
    }
}

/**
 * A parallel equivalent of [map]
 *
 * Starts the given suspending function for each item in the collection without waiting for
 * previous ones to complete, then suspends until all the started operations finish.
 */
suspend inline fun <T, R> Iterable<T>.mapInParallel(
    context: CoroutineContext,
    scope: CoroutineScope = GlobalScope,
    crossinline transform: suspend CoroutineScope.(T) -> R
): List<R> = map { scope.async(context) { transform(it) } }.map { it.await() }

/**
 * A parallel equivalent of [forEach]
 *
 * See [mapInParallel]
 */
suspend inline fun <T> Iterable<T>.forEachInParallel(
    context: CoroutineContext,
    scope: CoroutineScope = GlobalScope,
    crossinline action: suspend CoroutineScope.(T) -> Unit
) {
    mapInParallel(context, scope) { action(it) }
}

/**
 * Check that we haven't already started transitioning to a given destination. If we haven't,
 * start navigating to that destination.
 *
 * @param destResId The ID of the desired destination
 * @param args The optional bundle of args to be passed to the destination
 */
fun NavController.navigateSafe(destResId: Int, args: Bundle? = null) {
    val navAction = currentDestination?.getAction(destResId) ?: graph.getAction(destResId)
    navAction?.let { action ->
        if (currentDestination?.id != action.destinationId) {
            navigate(destResId, args)
        }
    }
}
/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.permissioncontroller.safetycenter.ui

import android.Manifest.permission_group.CAMERA as PERMISSION_GROUP_CAMERA
import android.Manifest.permission_group.LOCATION as PERMISSION_GROUP_LOCATION
import android.Manifest.permission_group.MICROPHONE as PERMISSION_GROUP_MICROPHONE
import android.content.Intent
import android.os.Build
import android.permission.PermissionGroupUsage
import android.permission.PermissionManager
import android.safetycenter.SafetyCenterEntry
import android.safetycenter.SafetyCenterIssue
import android.safetycenter.SafetyCenterManager.EXTRA_SAFETY_SOURCE_ISSUE_ID
import android.safetycenter.SafetyCenterStatus
import androidx.annotation.RequiresApi
import com.android.permissioncontroller.Constants
import com.android.permissioncontroller.PermissionControllerStatsLog
import com.android.permissioncontroller.permission.utils.Utils
import com.android.permissioncontroller.safetycenter.SafetyCenterConstants
import com.android.safetycenter.internaldata.SafetyCenterIds
import java.math.BigInteger
import java.security.MessageDigest

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class InteractionLogger(
    val noLogSourceIds: Set<String>,
    var sessionId: Long = Constants.INVALID_SESSION_ID,
    var viewType: ViewType = ViewType.UNKNOWN,
    var navigationSource: NavigationSource = NavigationSource.UNKNOWN,
    var navigationSensor: Sensor = Sensor.UNKNOWN
) {
    @JvmOverloads
    fun record(
        action: Action,
        severityLevel: LogSeverityLevel = LogSeverityLevel.UNKNOWN,
        sourceId: String? = null,
        sourceProfileType: SafetySourceProfileType = SafetySourceProfileType.UNKNOWN,
        issueTypeId: String? = null,
        sensor: Sensor = Sensor.UNKNOWN
    ) {
        if (noLogSourceIds.contains(sourceId)) {
            return
        }

        PermissionControllerStatsLog.write(
            PermissionControllerStatsLog.SAFETY_CENTER_INTERACTION_REPORTED,
            sessionId,
            action.statsLogValue,
            viewType.statsLogValue,
            navigationSource.statsLogValue,
            severityLevel.statsLogValue,
            encodeStringId(sourceId),
            sourceProfileType.statsLogValue,
            encodeStringId(issueTypeId),
            (if (sensor != Sensor.UNKNOWN) sensor else navigationSensor).statsLogValue)
    }

    fun recordForIssue(action: Action, issue: SafetyCenterIssue) {
        val decodedId = SafetyCenterIds.issueIdFromString(issue.id)
        record(
            action,
            LogSeverityLevel.fromIssueSeverityLevel(issue.severityLevel),
            sourceId = decodedId.safetyCenterIssueKey.safetySourceId,
            sourceProfileType =
                SafetySourceProfileType.fromUserId(decodedId.safetyCenterIssueKey.userId),
            issueTypeId = decodedId.issueTypeId)
    }

    fun recordForEntry(action: Action, entry: SafetyCenterEntry) {
        val decodedId = SafetyCenterIds.entryIdFromString(entry.id)
        record(
            action,
            LogSeverityLevel.fromEntrySeverityLevel(entry.severityLevel),
            sourceId = decodedId.safetySourceId,
            sourceProfileType = SafetySourceProfileType.fromUserId(decodedId.userId))
    }

    fun recordForSensor(action: Action, sensor: Sensor) {
        record(action = action, sensor = sensor)
    }

    private companion object {
        /**
         * Encodes a string into an long ID. The ID is a SHA-256 of the string, truncated to 64
         * bits.
         */
        private fun encodeStringId(id: String?): Long {
            if (id == null) return 0

            val digest = MessageDigest.getInstance("MD5")
            digest.update(id.toByteArray())

            // Truncate to the size of a long
            return BigInteger(digest.digest()).toLong()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
enum class Action(val statsLogValue: Int) {
    UNKNOWN(
        PermissionControllerStatsLog.SAFETY_CENTER_INTERACTION_REPORTED__ACTION__ACTION_UNKNOWN),
    SAFETY_CENTER_VIEWED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__SAFETY_CENTER_VIEWED),
    SAFETY_ISSUE_VIEWED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__SAFETY_ISSUE_VIEWED),
    SCAN_INITIATED(
        PermissionControllerStatsLog.SAFETY_CENTER_INTERACTION_REPORTED__ACTION__SCAN_INITIATED),
    ISSUE_PRIMARY_ACTION_CLICKED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__ISSUE_PRIMARY_ACTION_CLICKED),
    ISSUE_SECONDARY_ACTION_CLICKED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__ISSUE_SECONDARY_ACTION_CLICKED),
    ISSUE_DISMISS_CLICKED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__ISSUE_DISMISS_CLICKED),
    MORE_ISSUES_CLICKED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__MORE_ISSUES_CLICKED),
    ENTRY_CLICKED(
        PermissionControllerStatsLog.SAFETY_CENTER_INTERACTION_REPORTED__ACTION__ENTRY_CLICKED),
    ENTRY_ICON_ACTION_CLICKED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__ENTRY_ICON_ACTION_CLICKED),
    STATIC_ENTRY_CLICKED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__STATIC_ENTRY_CLICKED),
    PRIVACY_CONTROL_TOGGLE_CLICKED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__PRIVACY_CONTROL_TOGGLE_CLICKED),
    SENSOR_PERMISSION_REVOKE_CLICKED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__SENSOR_PERMISSION_REVOKE_CLICKED),
    SENSOR_PERMISSION_SEE_USAGES_CLICKED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__SENSOR_PERMISSION_SEE_USAGES_CLICKED),
    REVIEW_SETTINGS_CLICKED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__ACTION__REVIEW_SETTINGS_CLICKED)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
enum class ViewType(val statsLogValue: Int) {
    UNKNOWN(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__VIEW_TYPE__VIEW_TYPE_UNKNOWN),
    FULL(PermissionControllerStatsLog.SAFETY_CENTER_INTERACTION_REPORTED__VIEW_TYPE__FULL),
    QUICK_SETTINGS(
        PermissionControllerStatsLog.SAFETY_CENTER_INTERACTION_REPORTED__VIEW_TYPE__QUICK_SETTINGS)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
enum class NavigationSource(val statsLogValue: Int) {
    UNKNOWN(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__NAVIGATION_SOURCE__SOURCE_UNKNOWN),
    NOTIFICATION(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__NAVIGATION_SOURCE__NOTIFICATION),
    QUICK_SETTINGS_TILE(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__NAVIGATION_SOURCE__QUICK_SETTINGS_TILE),
    SETTINGS(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__NAVIGATION_SOURCE__SETTINGS),
    SENSOR_INDICATOR(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__NAVIGATION_SOURCE__SENSOR_INDICATOR);

    fun addToIntent(intent: Intent) {
        intent.putExtra(SafetyCenterConstants.EXTRA_NAVIGATION_SOURCE, this.toString())
    }

    companion object {
        @JvmStatic
        fun fromIntent(intent: Intent): NavigationSource =
            when (intent.action) {
                Intent.ACTION_SAFETY_CENTER -> fromSafetyCenterIntent(intent)
                Intent.ACTION_VIEW_SAFETY_CENTER_QS -> fromQuickSettingsIntent(intent)
                else -> UNKNOWN
            }

        private fun fromSafetyCenterIntent(intent: Intent): NavigationSource {
            val intentNavigationSource =
                intent.getStringExtra(SafetyCenterConstants.EXTRA_NAVIGATION_SOURCE)
            val sourceIssueId = intent.getStringExtra(EXTRA_SAFETY_SOURCE_ISSUE_ID)

            return if (sourceIssueId != null) {
                NOTIFICATION
            } else if (intentNavigationSource != null) {
                valueOf(intentNavigationSource)
            } else {
                UNKNOWN
            }
        }

        private fun fromQuickSettingsIntent(intent: Intent): NavigationSource {
            val usages =
                intent.getParcelableArrayListExtra(
                    PermissionManager.EXTRA_PERMISSION_USAGES, PermissionGroupUsage::class.java)

            return if (usages != null && usages.isNotEmpty()) {
                SENSOR_INDICATOR
            } else {
                QUICK_SETTINGS_TILE
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
enum class LogSeverityLevel(val statsLogValue: Int) {
    UNKNOWN(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__SEVERITY_LEVEL__SAFETY_SEVERITY_LEVEL_UNKNOWN),
    UNSPECIFIED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__SEVERITY_LEVEL__SAFETY_SEVERITY_UNSPECIFIED),
    OK(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__SEVERITY_LEVEL__SAFETY_SEVERITY_OK),
    RECOMMENDATION(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__SEVERITY_LEVEL__SAFETY_SEVERITY_RECOMMENDATION),
    CRITICAL_WARNING(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__SEVERITY_LEVEL__SAFETY_SEVERITY_CRITICAL_WARNING);

    companion object {
        @JvmStatic
        fun fromOverallSeverityLevel(overallLevel: Int): LogSeverityLevel =
            when (overallLevel) {
                SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN -> UNKNOWN
                SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK -> OK
                SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION -> RECOMMENDATION
                SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING -> CRITICAL_WARNING
                else -> UNKNOWN
            }

        @JvmStatic
        fun fromIssueSeverityLevel(issueLevel: Int): LogSeverityLevel =
            when (issueLevel) {
                SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK -> OK
                SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_RECOMMENDATION -> RECOMMENDATION
                SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_CRITICAL_WARNING -> CRITICAL_WARNING
                else -> UNKNOWN
            }

        @JvmStatic
        fun fromEntrySeverityLevel(entryLevel: Int): LogSeverityLevel =
            when (entryLevel) {
                SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK -> OK
                SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_RECOMMENDATION -> RECOMMENDATION
                SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING -> CRITICAL_WARNING
                SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED -> UNSPECIFIED
                else -> UNKNOWN
            }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
enum class SafetySourceProfileType(val statsLogValue: Int) {
    UNKNOWN(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__SAFETY_SOURCE_PROFILE_TYPE__PROFILE_TYPE_UNKNOWN),
    PERSONAL(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__SAFETY_SOURCE_PROFILE_TYPE__PROFILE_TYPE_PERSONAL),
    MANAGED(
        PermissionControllerStatsLog
            .SAFETY_CENTER_INTERACTION_REPORTED__SAFETY_SOURCE_PROFILE_TYPE__PROFILE_TYPE_MANAGED);

    companion object {
        @JvmStatic
        fun fromUserId(userId: Int): SafetySourceProfileType =
            if (Utils.isUserManagedProfile(userId)) MANAGED else PERSONAL
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
enum class Sensor(val statsLogValue: Int) {
    UNKNOWN(
        PermissionControllerStatsLog.SAFETY_CENTER_INTERACTION_REPORTED__SENSOR__SENSOR_UNKNOWN),
    MICROPHONE(PermissionControllerStatsLog.SAFETY_CENTER_INTERACTION_REPORTED__SENSOR__MICROPHONE),
    CAMERA(PermissionControllerStatsLog.SAFETY_CENTER_INTERACTION_REPORTED__SENSOR__CAMERA),
    LOCATION(PermissionControllerStatsLog.SAFETY_CENTER_INTERACTION_REPORTED__SENSOR__LOCATION);

    companion object {
        @JvmStatic
        fun fromIntent(intent: Intent): Sensor {
            if (intent.action != Intent.ACTION_VIEW_SAFETY_CENTER_QS) return UNKNOWN

            val usages =
                intent.getParcelableArrayListExtra(
                    PermissionManager.EXTRA_PERMISSION_USAGES, PermissionGroupUsage::class.java)

            // Multiple usages may be in effect, but we can only log one. Log unknown in this
            // scenario until we have a better solution (an explicit value approved for
            // logging).
            if (usages != null && usages.size > 1) return UNKNOWN

            return fromPermissionGroupUsage(usages?.firstOrNull())
        }

        @JvmStatic
        fun fromPermissionGroupUsage(usage: PermissionGroupUsage?) =
            fromPermissionGroupName(usage?.permissionGroupName)

        @JvmStatic
        fun fromPermissionGroupName(permissionGroupName: String?) =
            when (permissionGroupName) {
                PERMISSION_GROUP_CAMERA -> CAMERA
                PERMISSION_GROUP_MICROPHONE -> MICROPHONE
                PERMISSION_GROUP_LOCATION -> LOCATION
                else -> UNKNOWN
            }
    }
}

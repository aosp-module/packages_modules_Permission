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

package com.android.safetycenter;

import static android.os.Build.VERSION_CODES.TIRAMISU;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.icu.text.ListFormatter;
import android.icu.text.MessageFormat;
import android.icu.util.ULocale;
import android.safetycenter.SafetyCenterData;
import android.safetycenter.SafetyCenterEntry;
import android.safetycenter.SafetyCenterEntryGroup;
import android.safetycenter.SafetyCenterEntryOrGroup;
import android.safetycenter.SafetyCenterIssue;
import android.safetycenter.SafetyCenterStaticEntry;
import android.safetycenter.SafetyCenterStaticEntryGroup;
import android.safetycenter.SafetyCenterStatus;
import android.safetycenter.SafetySourceData;
import android.safetycenter.SafetySourceIssue;
import android.safetycenter.SafetySourceStatus;
import android.safetycenter.config.SafetyCenterConfig;
import android.safetycenter.config.SafetySource;
import android.safetycenter.config.SafetySourcesGroup;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.safetycenter.internaldata.SafetyCenterEntryGroupId;
import com.android.safetycenter.internaldata.SafetyCenterEntryId;
import com.android.safetycenter.internaldata.SafetyCenterIds;
import com.android.safetycenter.internaldata.SafetyCenterIssueActionId;
import com.android.safetycenter.internaldata.SafetyCenterIssueId;
import com.android.safetycenter.internaldata.SafetyCenterIssueKey;
import com.android.safetycenter.resources.SafetyCenterResourcesContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Aggregates {@link SafetySourceData} to build {@link SafetyCenterData} instances which are shared
 * with Safety Center listeners, including PermissionController.
 *
 * <p>This class isn't thread safe. Thread safety must be handled by the caller.
 */
@RequiresApi(TIRAMISU)
@NotThreadSafe
final class SafetyCenterDataFactory {

    private static final String TAG = "SafetyCenterDataFactory";

    private static final String ANDROID_LOCK_SCREEN_SOURCES_GROUP_ID = "AndroidLockScreenSources";

    private static final SafetyCenterIssuesBySeverityDescending
            SAFETY_CENTER_ISSUES_BY_SEVERITY_DESCENDING =
                    new SafetyCenterIssuesBySeverityDescending();

    @NonNull private final SafetyCenterResourcesContext mSafetyCenterResourcesContext;
    @NonNull private final SafetyCenterConfigReader mSafetyCenterConfigReader;
    @NonNull private final SafetyCenterRefreshTracker mSafetyCenterRefreshTracker;
    @NonNull private final PendingIntentFactory mPendingIntentFactory;
    @NonNull private final SafetyCenterIssueCache mSafetyCenterIssueCache;
    @NonNull private final SafetyCenterRepository mSafetyCenterRepository;

    SafetyCenterDataFactory(
            @NonNull SafetyCenterResourcesContext safetyCenterResourcesContext,
            @NonNull SafetyCenterConfigReader safetyCenterConfigReader,
            @NonNull SafetyCenterRefreshTracker safetyCenterRefreshTracker,
            @NonNull PendingIntentFactory pendingIntentFactory,
            @NonNull SafetyCenterIssueCache safetyCenterIssueCache,
            @NonNull SafetyCenterRepository safetyCenterRepository) {
        mSafetyCenterResourcesContext = safetyCenterResourcesContext;
        mSafetyCenterConfigReader = safetyCenterConfigReader;
        mSafetyCenterRefreshTracker = safetyCenterRefreshTracker;
        mPendingIntentFactory = pendingIntentFactory;
        mSafetyCenterIssueCache = safetyCenterIssueCache;
        mSafetyCenterRepository = safetyCenterRepository;
    }

    /**
     * Returns the current {@link SafetyCenterData} for the given {@code packageName} and {@link
     * UserProfileGroup}, aggregated from all the {@link SafetySourceData} set so far.
     *
     * <p>If a {@link SafetySourceData} was not set, the default value from the {@link
     * SafetyCenterConfig} is used.
     */
    @NonNull
    SafetyCenterData getSafetyCenterData(
            @NonNull String packageName, @NonNull UserProfileGroup userProfileGroup) {
        return getSafetyCenterData(
                mSafetyCenterConfigReader.getSafetySourcesGroups(), packageName, userProfileGroup);
    }

    /**
     * Returns a default {@link SafetyCenterData} object to be returned when the API is disabled.
     */
    @NonNull
    static SafetyCenterData getDefaultSafetyCenterData() {
        return new SafetyCenterData(
                new SafetyCenterStatus.Builder("", "")
                        .setSeverityLevel(SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN)
                        .build(),
                emptyList(),
                emptyList(),
                emptyList());
    }

    @NonNull
    private SafetyCenterData getSafetyCenterData(
            @NonNull List<SafetySourcesGroup> safetySourcesGroups,
            @NonNull String packageName,
            @NonNull UserProfileGroup userProfileGroup) {
        List<SafetyCenterIssueWithCategory> safetyCenterIssuesWithCategories = new ArrayList<>();
        List<SafetyCenterEntryOrGroup> safetyCenterEntryOrGroups = new ArrayList<>();
        List<SafetyCenterStaticEntryGroup> safetyCenterStaticEntryGroups = new ArrayList<>();
        SafetyCenterOverallState safetyCenterOverallState = new SafetyCenterOverallState();

        for (int i = 0; i < safetySourcesGroups.size(); i++) {
            SafetySourcesGroup safetySourcesGroup = safetySourcesGroups.get(i);

            addSafetyCenterIssues(
                    safetyCenterOverallState,
                    safetyCenterIssuesWithCategories,
                    safetySourcesGroup,
                    userProfileGroup);
            int safetySourcesGroupType = safetySourcesGroup.getType();
            switch (safetySourcesGroupType) {
                case SafetySourcesGroup.SAFETY_SOURCES_GROUP_TYPE_COLLAPSIBLE:
                    addSafetyCenterEntryGroup(
                            safetyCenterOverallState,
                            safetyCenterEntryOrGroups,
                            safetySourcesGroup,
                            packageName,
                            userProfileGroup);
                    break;
                case SafetySourcesGroup.SAFETY_SOURCES_GROUP_TYPE_RIGID:
                    addSafetyCenterStaticEntryGroup(
                            safetyCenterOverallState,
                            safetyCenterStaticEntryGroups,
                            safetySourcesGroup,
                            packageName,
                            userProfileGroup);
                    break;
                case SafetySourcesGroup.SAFETY_SOURCES_GROUP_TYPE_HIDDEN:
                    break;
                default:
                    Log.w(TAG, "Unexpected SafetySourceGroupType: " + safetySourcesGroupType);
                    break;
            }
        }

        safetyCenterIssuesWithCategories.sort(SAFETY_CENTER_ISSUES_BY_SEVERITY_DESCENDING);

        List<SafetyCenterIssue> safetyCenterIssues =
                new ArrayList<>(safetyCenterIssuesWithCategories.size());
        for (int i = 0; i < safetyCenterIssuesWithCategories.size(); i++) {
            safetyCenterIssues.add(safetyCenterIssuesWithCategories.get(i).getSafetyCenterIssue());
        }

        int refreshStatus = mSafetyCenterRefreshTracker.getRefreshStatus();
        return new SafetyCenterData(
                new SafetyCenterStatus.Builder(
                                getSafetyCenterStatusTitle(
                                        safetyCenterOverallState.getOverallSeverityLevel(),
                                        safetyCenterIssuesWithCategories,
                                        refreshStatus,
                                        safetyCenterOverallState.hasSettingsToReview()),
                                getSafetyCenterStatusSummary(
                                        safetyCenterOverallState.getOverallSeverityLevel(),
                                        refreshStatus,
                                        safetyCenterIssues.size(),
                                        safetyCenterOverallState.hasSettingsToReview()))
                        .setSeverityLevel(safetyCenterOverallState.getOverallSeverityLevel())
                        .setRefreshStatus(refreshStatus)
                        .build(),
                safetyCenterIssues,
                safetyCenterEntryOrGroups,
                safetyCenterStaticEntryGroups);
    }

    private void addSafetyCenterIssues(
            @NonNull SafetyCenterOverallState safetyCenterOverallState,
            @NonNull List<SafetyCenterIssueWithCategory> safetyCenterIssuesWithCategories,
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @NonNull UserProfileGroup userProfileGroup) {
        List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();
        for (int i = 0; i < safetySources.size(); i++) {
            SafetySource safetySource = safetySources.get(i);

            if (!SafetySources.isExternal(safetySource)) {
                continue;
            }

            addSafetyCenterIssues(
                    safetyCenterOverallState,
                    safetyCenterIssuesWithCategories,
                    safetySource,
                    userProfileGroup.getProfileParentUserId());

            if (!SafetySources.supportsManagedProfiles(safetySource)) {
                continue;
            }

            int[] managedRunningProfilesUserIds =
                    userProfileGroup.getManagedRunningProfilesUserIds();
            for (int j = 0; j < managedRunningProfilesUserIds.length; j++) {
                int managedRunningProfileUserId = managedRunningProfilesUserIds[j];

                addSafetyCenterIssues(
                        safetyCenterOverallState,
                        safetyCenterIssuesWithCategories,
                        safetySource,
                        managedRunningProfileUserId);
            }
        }
    }

    private void addSafetyCenterIssues(
            @NonNull SafetyCenterOverallState safetyCenterOverallState,
            @NonNull List<SafetyCenterIssueWithCategory> safetyCenterIssuesWithCategories,
            @NonNull SafetySource safetySource,
            @UserIdInt int userId) {
        SafetySourceKey key = SafetySourceKey.of(safetySource.getId(), userId);
        SafetySourceData safetySourceData = mSafetyCenterRepository.getSafetySourceData(key);

        if (safetySourceData == null) {
            return;
        }

        List<SafetySourceIssue> safetySourceIssues = safetySourceData.getIssues();
        for (int i = 0; i < safetySourceIssues.size(); i++) {
            SafetySourceIssue safetySourceIssue = safetySourceIssues.get(i);
            SafetyCenterIssue safetyCenterIssue =
                    toSafetyCenterIssue(safetySourceIssue, safetySource, userId);

            if (safetyCenterIssue == null) {
                continue;
            }

            safetyCenterOverallState.addIssueOverallSeverityLevel(
                    toSafetyCenterStatusOverallSeverityLevel(safetySourceIssue.getSeverityLevel()));
            safetyCenterIssuesWithCategories.add(
                    SafetyCenterIssueWithCategory.create(
                            safetyCenterIssue, safetySourceIssue.getIssueCategory()));
        }
    }

    @Nullable
    private SafetyCenterIssue toSafetyCenterIssue(
            @NonNull SafetySourceIssue safetySourceIssue,
            @NonNull SafetySource safetySource,
            @UserIdInt int userId) {
        SafetyCenterIssueId safetyCenterIssueId =
                SafetyCenterIssueId.newBuilder()
                        .setSafetyCenterIssueKey(
                                SafetyCenterIssueKey.newBuilder()
                                        .setSafetySourceId(safetySource.getId())
                                        .setSafetySourceIssueId(safetySourceIssue.getId())
                                        .setUserId(userId)
                                        .build())
                        .setIssueTypeId(safetySourceIssue.getIssueTypeId())
                        .build();

        if (mSafetyCenterIssueCache.isIssueDismissed(
                safetyCenterIssueId.getSafetyCenterIssueKey(),
                safetySourceIssue.getSeverityLevel())) {
            return null;
        }

        List<SafetySourceIssue.Action> safetySourceIssueActions = safetySourceIssue.getActions();
        List<SafetyCenterIssue.Action> safetyCenterIssueActions =
                new ArrayList<>(safetySourceIssueActions.size());
        for (int i = 0; i < safetySourceIssueActions.size(); i++) {
            SafetySourceIssue.Action safetySourceIssueAction = safetySourceIssueActions.get(i);

            safetyCenterIssueActions.add(
                    toSafetyCenterIssueAction(
                            safetySourceIssueAction,
                            safetyCenterIssueId.getSafetyCenterIssueKey()));
        }

        int safetyCenterIssueSeverityLevel =
                toSafetyCenterIssueSeverityLevel(safetySourceIssue.getSeverityLevel());
        return new SafetyCenterIssue.Builder(
                        SafetyCenterIds.encodeToString(safetyCenterIssueId),
                        safetySourceIssue.getTitle(),
                        safetySourceIssue.getSummary())
                .setSeverityLevel(safetyCenterIssueSeverityLevel)
                .setShouldConfirmDismissal(
                        safetyCenterIssueSeverityLevel > SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK)
                .setSubtitle(safetySourceIssue.getSubtitle())
                .setActions(safetyCenterIssueActions)
                .build();
    }

    @NonNull
    private SafetyCenterIssue.Action toSafetyCenterIssueAction(
            @NonNull SafetySourceIssue.Action safetySourceIssueAction,
            @NonNull SafetyCenterIssueKey safetyCenterIssueKey) {
        SafetyCenterIssueActionId safetyCenterIssueActionId =
                SafetyCenterIssueActionId.newBuilder()
                        .setSafetyCenterIssueKey(safetyCenterIssueKey)
                        .setSafetySourceIssueActionId(safetySourceIssueAction.getId())
                        .build();
        PendingIntent issueActionPendingIntent =
                mPendingIntentFactory.maybeOverridePendingIntent(
                        safetyCenterIssueKey.getSafetySourceId(),
                        safetySourceIssueAction.getPendingIntent(),
                        false);
        return new SafetyCenterIssue.Action.Builder(
                        SafetyCenterIds.encodeToString(safetyCenterIssueActionId),
                        safetySourceIssueAction.getLabel(),
                        requireNonNull(issueActionPendingIntent))
                .setSuccessMessage(safetySourceIssueAction.getSuccessMessage())
                .setIsInFlight(mSafetyCenterRepository.actionIsInFlight(safetyCenterIssueActionId))
                .setWillResolve(safetySourceIssueAction.willResolve())
                .build();
    }

    private void addSafetyCenterEntryGroup(
            @NonNull SafetyCenterOverallState safetyCenterOverallState,
            @NonNull List<SafetyCenterEntryOrGroup> safetyCenterEntryOrGroups,
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @NonNull String defaultPackageName,
            @NonNull UserProfileGroup userProfileGroup) {
        int groupSafetyCenterEntryLevel = SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED;

        List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();
        List<SafetyCenterEntry> entries = new ArrayList<>(safetySources.size());
        for (int i = 0; i < safetySources.size(); i++) {
            SafetySource safetySource = safetySources.get(i);

            groupSafetyCenterEntryLevel =
                    mergeSafetyCenterEntrySeverityLevels(
                            groupSafetyCenterEntryLevel,
                            addSafetyCenterEntry(
                                    safetyCenterOverallState,
                                    entries,
                                    safetySource,
                                    defaultPackageName,
                                    userProfileGroup.getProfileParentUserId(),
                                    false,
                                    false));

            if (!SafetySources.supportsManagedProfiles(safetySource)) {
                continue;
            }

            int[] managedProfilesUserIds = userProfileGroup.getManagedProfilesUserIds();
            for (int j = 0; j < managedProfilesUserIds.length; j++) {
                int managedProfileUserId = managedProfilesUserIds[j];
                boolean isManagedUserRunning =
                        userProfileGroup.isManagedUserRunning(managedProfileUserId);

                groupSafetyCenterEntryLevel =
                        mergeSafetyCenterEntrySeverityLevels(
                                groupSafetyCenterEntryLevel,
                                addSafetyCenterEntry(
                                        safetyCenterOverallState,
                                        entries,
                                        safetySource,
                                        defaultPackageName,
                                        managedProfileUserId,
                                        true,
                                        isManagedUserRunning));
            }
        }

        if (entries.size() == 0) {
            return;
        }

        if (entries.size() == 1) {
            safetyCenterEntryOrGroups.add(new SafetyCenterEntryOrGroup(entries.get(0)));
            return;
        }

        SafetyCenterEntryGroupId safetyCenterEntryGroupId =
                SafetyCenterEntryGroupId.newBuilder()
                        .setSafetySourcesGroupId(safetySourcesGroup.getId())
                        .build();
        CharSequence groupSummary =
                getSafetyCenterEntryGroupSummary(
                        safetySourcesGroup, groupSafetyCenterEntryLevel, entries);
        safetyCenterEntryOrGroups.add(
                new SafetyCenterEntryOrGroup(
                        new SafetyCenterEntryGroup.Builder(
                                        SafetyCenterIds.encodeToString(safetyCenterEntryGroupId),
                                        mSafetyCenterResourcesContext.getString(
                                                safetySourcesGroup.getTitleResId()))
                                .setSeverityLevel(groupSafetyCenterEntryLevel)
                                .setSummary(groupSummary)
                                .setEntries(entries)
                                .setSeverityUnspecifiedIconType(
                                        toGroupSeverityUnspecifiedIconType(
                                                safetySourcesGroup.getStatelessIconType()))
                                .build()));
    }

    @SafetyCenterEntry.EntrySeverityLevel
    private static int mergeSafetyCenterEntrySeverityLevels(
            @SafetyCenterEntry.EntrySeverityLevel int left,
            @SafetyCenterEntry.EntrySeverityLevel int right) {
        if (left > SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK
                || right > SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK) {
            return Math.max(left, right);
        }
        if (left == SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN
                || right == SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN) {
            return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN;
        }
        return Math.max(left, right);
    }

    @Nullable
    private CharSequence getSafetyCenterEntryGroupSummary(
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @SafetyCenterEntry.EntrySeverityLevel int groupSafetyCenterEntryLevel,
            @NonNull List<SafetyCenterEntry> entries) {
        switch (groupSafetyCenterEntryLevel) {
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING:
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_RECOMMENDATION:
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK:
                for (int i = 0; i < entries.size(); i++) {
                    SafetyCenterEntry entry = entries.get(i);

                    CharSequence entrySummary = entry.getSummary();
                    if (entry.getSeverityLevel() != groupSafetyCenterEntryLevel
                            || entrySummary == null) {
                        continue;
                    }

                    if (groupSafetyCenterEntryLevel > SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK) {
                        return entrySummary;
                    }

                    SafetySourceKey key = toSafetySourceKey(entry.getId());
                    SafetySourceData safetySourceData =
                            mSafetyCenterRepository.getSafetySourceData(key);
                    boolean hasIssues =
                            safetySourceData != null && !safetySourceData.getIssues().isEmpty();

                    if (hasIssues) {
                        return entrySummary;
                    }
                }

                return getDefaultGroupSummary(safetySourcesGroup, entries);
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED:
                return getDefaultGroupSummary(safetySourcesGroup, entries);
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN:
                int errorEntries = 0;
                for (int i = 0; i < entries.size(); i++) {
                    SafetyCenterEntry entry = entries.get(i);

                    SafetySourceKey key = toSafetySourceKey(entry.getId());
                    if (mSafetyCenterRepository.sourceHasError(key)) {
                        errorEntries++;
                    }
                }

                if (errorEntries > 0) {
                    return getRefreshErrorString(errorEntries);
                }

                return mSafetyCenterResourcesContext.getStringByName("group_unknown_summary");
        }

        Log.w(
                TAG,
                "Unexpected SafetyCenterEntry.EntrySeverityLevel for SafetyCenterEntryGroup: "
                        + groupSafetyCenterEntryLevel);
        return getDefaultGroupSummary(safetySourcesGroup, entries);
    }

    @Nullable
    private CharSequence getDefaultGroupSummary(
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @NonNull List<SafetyCenterEntry> entries) {
        CharSequence groupSummary =
                mSafetyCenterResourcesContext.getOptionalString(
                        safetySourcesGroup.getSummaryResId());

        if (safetySourcesGroup.getId().equals(ANDROID_LOCK_SCREEN_SOURCES_GROUP_ID)
                && TextUtils.isEmpty(groupSummary)) {
            List<CharSequence> titles = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                titles.add(entries.get(i).getTitle());
            }
            groupSummary =
                    ListFormatter.getInstance(
                                    ULocale.getDefault(ULocale.Category.FORMAT),
                                    ListFormatter.Type.AND,
                                    ListFormatter.Width.NARROW)
                            .format(titles);
        }

        return groupSummary;
    }

    @SafetyCenterEntry.EntrySeverityLevel
    private int addSafetyCenterEntry(
            @NonNull SafetyCenterOverallState safetyCenterOverallState,
            @NonNull List<SafetyCenterEntry> entries,
            @NonNull SafetySource safetySource,
            @NonNull String defaultPackageName,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        SafetyCenterEntry safetyCenterEntry =
                toSafetyCenterEntry(
                        safetySource,
                        defaultPackageName,
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
        if (safetyCenterEntry == null) {
            return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED;
        }

        safetyCenterOverallState.addEntryOverallSeverityLevel(
                entryToSafetyCenterStatusOverallSeverityLevel(
                        safetyCenterEntry.getSeverityLevel()));
        entries.add(safetyCenterEntry);

        return safetyCenterEntry.getSeverityLevel();
    }

    @Nullable
    private SafetyCenterEntry toSafetyCenterEntry(
            @NonNull SafetySource safetySource,
            @NonNull String defaultPackageName,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        switch (safetySource.getType()) {
            case SafetySource.SAFETY_SOURCE_TYPE_ISSUE_ONLY:
                return null;
            case SafetySource.SAFETY_SOURCE_TYPE_DYNAMIC:
                SafetySourceKey key = SafetySourceKey.of(safetySource.getId(), userId);
                SafetySourceStatus safetySourceStatus =
                        getSafetySourceStatus(mSafetyCenterRepository.getSafetySourceData(key));
                boolean defaultEntryDueToQuietMode = isUserManaged && !isManagedUserRunning;
                if (safetySourceStatus == null || defaultEntryDueToQuietMode) {
                    return toDefaultSafetyCenterEntry(
                            safetySource,
                            safetySource.getPackageName(),
                            SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN,
                            SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION,
                            userId,
                            isUserManaged,
                            isManagedUserRunning);
                }
                PendingIntent entryPendingIntent = safetySourceStatus.getPendingIntent();
                boolean enabled = safetySourceStatus.isEnabled();
                if (entryPendingIntent == null) {
                    entryPendingIntent =
                            mPendingIntentFactory.getPendingIntent(
                                    safetySource.getId(),
                                    safetySource.getIntentAction(),
                                    safetySource.getPackageName(),
                                    userId,
                                    false);
                    enabled = enabled && entryPendingIntent != null;
                }
                SafetyCenterEntryId safetyCenterEntryId =
                        SafetyCenterEntryId.newBuilder()
                                .setSafetySourceId(safetySource.getId())
                                .setUserId(userId)
                                .build();
                int severityUnspecifiedIconType =
                        SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION;
                int severityLevel =
                        enabled
                                ? toSafetyCenterEntrySeverityLevel(
                                        safetySourceStatus.getSeverityLevel())
                                : SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED;
                SafetyCenterEntry.Builder builder =
                        new SafetyCenterEntry.Builder(
                                        SafetyCenterIds.encodeToString(safetyCenterEntryId),
                                        safetySourceStatus.getTitle())
                                .setSeverityLevel(severityLevel)
                                .setSummary(safetySourceStatus.getSummary())
                                .setEnabled(enabled)
                                .setSeverityUnspecifiedIconType(severityUnspecifiedIconType)
                                .setPendingIntent(
                                        mPendingIntentFactory.maybeOverridePendingIntent(
                                                safetySource.getId(), entryPendingIntent, false));
                SafetySourceStatus.IconAction iconAction = safetySourceStatus.getIconAction();
                if (iconAction == null) {
                    return builder.build();
                }
                PendingIntent iconActionPendingIntent =
                        mPendingIntentFactory.maybeOverridePendingIntent(
                                safetySource.getId(), iconAction.getPendingIntent(), true);
                return builder.setIconAction(
                                new SafetyCenterEntry.IconAction(
                                        toSafetyCenterEntryIconActionType(iconAction.getIconType()),
                                        requireNonNull(iconActionPendingIntent)))
                        .build();
            case SafetySource.SAFETY_SOURCE_TYPE_STATIC:
                return toDefaultSafetyCenterEntry(
                        safetySource,
                        defaultPackageName,
                        SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED,
                        SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_ICON,
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
        }
        Log.w(
                TAG,
                "Unknown safety source type found in collapsible group: " + safetySource.getType());
        return null;
    }

    @Nullable
    private SafetyCenterEntry toDefaultSafetyCenterEntry(
            @NonNull SafetySource safetySource,
            @NonNull String packageName,
            @SafetyCenterEntry.EntrySeverityLevel int entrySeverityLevel,
            @SafetyCenterEntry.SeverityUnspecifiedIconType int severityUnspecifiedIconType,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        if (SafetySources.isDefaultEntryHidden(safetySource)) {
            return null;
        }

        SafetyCenterEntryId safetyCenterEntryId =
                SafetyCenterEntryId.newBuilder()
                        .setSafetySourceId(safetySource.getId())
                        .setUserId(userId)
                        .build();
        boolean isQuietModeEnabled = isUserManaged && !isManagedUserRunning;
        PendingIntent pendingIntent =
                mPendingIntentFactory.getPendingIntent(
                        safetySource.getId(),
                        safetySource.getIntentAction(),
                        packageName,
                        userId,
                        isQuietModeEnabled);
        boolean enabled =
                pendingIntent != null && !SafetySources.isDefaultEntryDisabled(safetySource);
        CharSequence title =
                isUserManaged
                        ? DevicePolicyResources.getSafetySourceWorkString(
                                mSafetyCenterResourcesContext,
                                safetySource.getId(),
                                safetySource.getTitleForWorkResId())
                        : mSafetyCenterResourcesContext.getString(safetySource.getTitleResId());
        CharSequence summary =
                mSafetyCenterRepository.sourceHasError(
                                SafetySourceKey.of(safetySource.getId(), userId))
                        ? getRefreshErrorString(1)
                        : mSafetyCenterResourcesContext.getOptionalString(
                                safetySource.getSummaryResId());
        if (isQuietModeEnabled) {
            enabled = false;
            summary =
                    DevicePolicyResources.getWorkProfilePausedString(mSafetyCenterResourcesContext);
        }
        return new SafetyCenterEntry.Builder(
                        SafetyCenterIds.encodeToString(safetyCenterEntryId), title)
                .setSeverityLevel(entrySeverityLevel)
                .setSummary(summary)
                .setEnabled(enabled)
                .setPendingIntent(pendingIntent)
                .setSeverityUnspecifiedIconType(severityUnspecifiedIconType)
                .build();
    }

    private void addSafetyCenterStaticEntryGroup(
            @NonNull SafetyCenterOverallState safetyCenterOverallState,
            @NonNull List<SafetyCenterStaticEntryGroup> safetyCenterStaticEntryGroups,
            @NonNull SafetySourcesGroup safetySourcesGroup,
            @NonNull String defaultPackageName,
            @NonNull UserProfileGroup userProfileGroup) {
        List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();
        List<SafetyCenterStaticEntry> staticEntries = new ArrayList<>(safetySources.size());
        for (int i = 0; i < safetySources.size(); i++) {
            SafetySource safetySource = safetySources.get(i);

            addSafetyCenterStaticEntry(
                    safetyCenterOverallState,
                    staticEntries,
                    safetySource,
                    defaultPackageName,
                    userProfileGroup.getProfileParentUserId(),
                    false,
                    false);

            if (!SafetySources.supportsManagedProfiles(safetySource)) {
                continue;
            }

            int[] managedProfilesUserIds = userProfileGroup.getManagedProfilesUserIds();
            for (int j = 0; j < managedProfilesUserIds.length; j++) {
                int managedProfileUserId = managedProfilesUserIds[j];
                boolean isManagedUserRunning =
                        userProfileGroup.isManagedUserRunning(managedProfileUserId);

                addSafetyCenterStaticEntry(
                        safetyCenterOverallState,
                        staticEntries,
                        safetySource,
                        defaultPackageName,
                        managedProfileUserId,
                        true,
                        isManagedUserRunning);
            }
        }

        safetyCenterStaticEntryGroups.add(
                new SafetyCenterStaticEntryGroup(
                        mSafetyCenterResourcesContext.getString(safetySourcesGroup.getTitleResId()),
                        staticEntries));
    }

    private void addSafetyCenterStaticEntry(
            @NonNull SafetyCenterOverallState safetyCenterOverallState,
            @NonNull List<SafetyCenterStaticEntry> staticEntries,
            @NonNull SafetySource safetySource,
            @NonNull String defaultPackageName,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        SafetyCenterStaticEntry staticEntry =
                toSafetyCenterStaticEntry(
                        safetySource,
                        defaultPackageName,
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
        if (staticEntry == null) {
            return;
        }
        boolean isQuietModeEnabled = isUserManaged && !isManagedUserRunning;
        boolean hasError =
                mSafetyCenterRepository.sourceHasError(
                        SafetySourceKey.of(safetySource.getId(), userId));
        if (isQuietModeEnabled || hasError) {
            safetyCenterOverallState.addEntryOverallSeverityLevel(
                    SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN);
        }
        staticEntries.add(staticEntry);
    }

    @Nullable
    private SafetyCenterStaticEntry toSafetyCenterStaticEntry(
            @NonNull SafetySource safetySource,
            @NonNull String defaultPackageName,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        switch (safetySource.getType()) {
            case SafetySource.SAFETY_SOURCE_TYPE_ISSUE_ONLY:
                return null;
            case SafetySource.SAFETY_SOURCE_TYPE_DYNAMIC:
                SafetySourceKey key = SafetySourceKey.of(safetySource.getId(), userId);
                SafetySourceStatus safetySourceStatus =
                        getSafetySourceStatus(mSafetyCenterRepository.getSafetySourceData(key));
                boolean defaultEntryDueToQuietMode = isUserManaged && !isManagedUserRunning;
                if (safetySourceStatus != null && !defaultEntryDueToQuietMode) {
                    PendingIntent pendingIntent = safetySourceStatus.getPendingIntent();
                    if (pendingIntent == null) {
                        // TODO(b/222838784): Decide strategy for static entries when the intent is
                        //  null.
                        return null;
                    }
                    return new SafetyCenterStaticEntry.Builder(safetySourceStatus.getTitle())
                            .setSummary(safetySourceStatus.getSummary())
                            .setPendingIntent(pendingIntent)
                            .build();
                }
                return toDefaultSafetyCenterStaticEntry(
                        safetySource,
                        safetySource.getPackageName(),
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
            case SafetySource.SAFETY_SOURCE_TYPE_STATIC:
                return toDefaultSafetyCenterStaticEntry(
                        safetySource,
                        defaultPackageName,
                        userId,
                        isUserManaged,
                        isManagedUserRunning);
        }
        Log.w(TAG, "Unknown safety source type found in rigid group: " + safetySource.getType());
        return null;
    }

    @Nullable
    private SafetyCenterStaticEntry toDefaultSafetyCenterStaticEntry(
            @NonNull SafetySource safetySource,
            @NonNull String packageName,
            @UserIdInt int userId,
            boolean isUserManaged,
            boolean isManagedUserRunning) {
        if (SafetySources.isDefaultEntryHidden(safetySource)) {
            return null;
        }
        boolean isQuietModeEnabled = isUserManaged && !isManagedUserRunning;
        PendingIntent pendingIntent =
                mPendingIntentFactory.getPendingIntent(
                        safetySource.getId(),
                        safetySource.getIntentAction(),
                        packageName,
                        userId,
                        isQuietModeEnabled);

        if (pendingIntent == null) {
            // TODO(b/222838784): Decide strategy for static entries when the intent is null.
            return null;
        }

        CharSequence title =
                isUserManaged
                        ? DevicePolicyResources.getSafetySourceWorkString(
                                mSafetyCenterResourcesContext,
                                safetySource.getId(),
                                safetySource.getTitleForWorkResId())
                        : mSafetyCenterResourcesContext.getString(safetySource.getTitleResId());
        CharSequence summary =
                mSafetyCenterRepository.sourceHasError(
                                SafetySourceKey.of(safetySource.getId(), userId))
                        ? getRefreshErrorString(1)
                        : mSafetyCenterResourcesContext.getOptionalString(
                                safetySource.getSummaryResId());
        if (isQuietModeEnabled) {
            summary =
                    DevicePolicyResources.getWorkProfilePausedString(mSafetyCenterResourcesContext);
        }
        return new SafetyCenterStaticEntry.Builder(title)
                .setSummary(summary)
                .setPendingIntent(pendingIntent)
                .build();
    }

    @Nullable
    private static SafetySourceStatus getSafetySourceStatus(
            @Nullable SafetySourceData safetySourceData) {
        if (safetySourceData == null) {
            return null;
        }

        return safetySourceData.getStatus();
    }

    @SafetyCenterStatus.OverallSeverityLevel
    private static int toSafetyCenterStatusOverallSeverityLevel(
            @SafetySourceData.SeverityLevel int safetySourceSeverityLevel) {
        switch (safetySourceSeverityLevel) {
            case SafetySourceData.SEVERITY_LEVEL_UNSPECIFIED:
            case SafetySourceData.SEVERITY_LEVEL_INFORMATION:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;
            case SafetySourceData.SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetySourceData.SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        Log.w(TAG, "Unexpected SafetySourceData.SeverityLevel: " + safetySourceSeverityLevel);
        return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN;
    }

    @SafetyCenterStatus.OverallSeverityLevel
    private static int entryToSafetyCenterStatusOverallSeverityLevel(
            @SafetyCenterEntry.EntrySeverityLevel int safetyCenterEntrySeverityLevel) {
        switch (safetyCenterEntrySeverityLevel) {
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN;
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED:
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        Log.w(
                TAG,
                "Unexpected SafetyCenterEntry.EntrySeverityLevel: "
                        + safetyCenterEntrySeverityLevel);
        return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN;
    }

    @SafetyCenterEntry.EntrySeverityLevel
    private static int toSafetyCenterEntrySeverityLevel(
            @SafetySourceData.SeverityLevel int safetySourceSeverityLevel) {
        switch (safetySourceSeverityLevel) {
            case SafetySourceData.SEVERITY_LEVEL_UNSPECIFIED:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED;
            case SafetySourceData.SEVERITY_LEVEL_INFORMATION:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_OK;
            case SafetySourceData.SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetySourceData.SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        Log.w(
                TAG,
                "Unexpected SafetySourceData.SeverityLevel in SafetySourceStatus: "
                        + safetySourceSeverityLevel);
        return SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN;
    }

    @SafetyCenterIssue.IssueSeverityLevel
    private static int toSafetyCenterIssueSeverityLevel(
            @SafetySourceData.SeverityLevel int safetySourceIssueSeverityLevel) {
        switch (safetySourceIssueSeverityLevel) {
            case SafetySourceData.SEVERITY_LEVEL_UNSPECIFIED:
                Log.w(
                        TAG,
                        "Unexpected use of SafetySourceData.SEVERITY_LEVEL_UNSPECIFIED in "
                                + "SafetySourceIssue");
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK;
            case SafetySourceData.SEVERITY_LEVEL_INFORMATION:
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK;
            case SafetySourceData.SEVERITY_LEVEL_RECOMMENDATION:
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_RECOMMENDATION;
            case SafetySourceData.SEVERITY_LEVEL_CRITICAL_WARNING:
                return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_CRITICAL_WARNING;
        }

        Log.w(
                TAG,
                "Unexpected SafetySourceData.SeverityLevel in SafetySourceIssue: "
                        + safetySourceIssueSeverityLevel);
        return SafetyCenterIssue.ISSUE_SEVERITY_LEVEL_OK;
    }

    @SafetyCenterEntry.SeverityUnspecifiedIconType
    private static int toGroupSeverityUnspecifiedIconType(
            @SafetySourcesGroup.StatelessIconType int statelessIconType) {
        switch (statelessIconType) {
            case SafetySourcesGroup.STATELESS_ICON_TYPE_NONE:
                return SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_ICON;
            case SafetySourcesGroup.STATELESS_ICON_TYPE_PRIVACY:
                return SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_PRIVACY;
        }

        Log.w(TAG, "Unexpected SafetySourcesGroup.StatelessIconType: " + statelessIconType);
        return SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_ICON;
    }

    @SafetyCenterEntry.IconAction.IconActionType
    private static int toSafetyCenterEntryIconActionType(
            @SafetySourceStatus.IconAction.IconType int safetySourceIconActionType) {
        switch (safetySourceIconActionType) {
            case SafetySourceStatus.IconAction.ICON_TYPE_GEAR:
                return SafetyCenterEntry.IconAction.ICON_ACTION_TYPE_GEAR;
            case SafetySourceStatus.IconAction.ICON_TYPE_INFO:
                return SafetyCenterEntry.IconAction.ICON_ACTION_TYPE_INFO;
        }

        Log.w(
                TAG,
                "Unexpected SafetySourceStatus.IconAction.IconActionType: "
                        + safetySourceIconActionType);
        return SafetyCenterEntry.IconAction.ICON_ACTION_TYPE_INFO;
    }

    @NonNull
    private String getSafetyCenterStatusTitle(
            @SafetyCenterStatus.OverallSeverityLevel int overallSeverityLevel,
            @NonNull List<SafetyCenterIssueWithCategory> safetyCenterIssuesWithCategories,
            @SafetyCenterStatus.RefreshStatus int refreshStatus,
            boolean hasSettingsToReview) {
        boolean overallSeverityUnknown =
                overallSeverityLevel == SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN;
        String refreshStatusTitle =
                getSafetyCenterRefreshStatusTitle(refreshStatus, overallSeverityUnknown);
        if (refreshStatusTitle != null) {
            return refreshStatusTitle;
        }
        switch (overallSeverityLevel) {
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN:
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK:
                if (hasSettingsToReview) {
                    return mSafetyCenterResourcesContext.getStringByName(
                            "overall_severity_level_ok_review_title");
                }
                return mSafetyCenterResourcesContext.getStringByName(
                        "overall_severity_level_ok_title");
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION:
                return getStatusTitleFromIssueCategories(
                        safetyCenterIssuesWithCategories,
                        "overall_severity_level_device_recommendation_title",
                        "overall_severity_level_account_recommendation_title",
                        "overall_severity_level_safety_recommendation_title");
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING:
                return getStatusTitleFromIssueCategories(
                        safetyCenterIssuesWithCategories,
                        "overall_severity_level_critical_device_warning_title",
                        "overall_severity_level_critical_account_warning_title",
                        "overall_severity_level_critical_safety_warning_title");
        }

        Log.w(TAG, "Unexpected SafetyCenterStatus.OverallSeverityLevel: " + overallSeverityLevel);
        return "";
    }

    @NonNull
    private String getStatusTitleFromIssueCategories(
            @NonNull List<SafetyCenterIssueWithCategory> safetyCenterIssuesWithCategories,
            @NonNull String deviceResourceName,
            @NonNull String accountResourceName,
            @NonNull String generalResourceName) {
        String generalString = mSafetyCenterResourcesContext.getStringByName(generalResourceName);
        if (safetyCenterIssuesWithCategories.isEmpty()) {
            Log.w(TAG, "No safety center issues found in a non-green status");
            return generalString;
        }
        int issueCategory = safetyCenterIssuesWithCategories.get(0).getSafetyCenterIssueCategory();
        switch (issueCategory) {
            case SafetySourceIssue.ISSUE_CATEGORY_DEVICE:
                return mSafetyCenterResourcesContext.getStringByName(deviceResourceName);
            case SafetySourceIssue.ISSUE_CATEGORY_ACCOUNT:
                return mSafetyCenterResourcesContext.getStringByName(accountResourceName);
            case SafetySourceIssue.ISSUE_CATEGORY_GENERAL:
                return generalString;
        }

        Log.w(TAG, "Unexpected SafetySourceIssue.IssueCategory: " + issueCategory);
        return generalString;
    }

    @NonNull
    private String getSafetyCenterStatusSummary(
            @SafetyCenterStatus.OverallSeverityLevel int overallSeverityLevel,
            @SafetyCenterStatus.RefreshStatus int refreshStatus,
            int numberOfIssues,
            boolean hasSettingsToReview) {
        String refreshStatusSummary = getSafetyCenterRefreshStatusSummary(refreshStatus);
        if (refreshStatusSummary != null) {
            return refreshStatusSummary;
        }
        switch (overallSeverityLevel) {
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN:
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK:
                if (numberOfIssues == 0) {
                    if (hasSettingsToReview) {
                        return mSafetyCenterResourcesContext.getStringByName(
                                "overall_severity_level_ok_review_summary");
                    }
                    return mSafetyCenterResourcesContext.getStringByName(
                            "overall_severity_level_ok_summary");
                }
                // Fall through.
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_RECOMMENDATION:
            case SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_CRITICAL_WARNING:
                return getIcuPluralsString("overall_severity_n_alerts_summary", numberOfIssues);
        }

        Log.w(TAG, "Unexpected SafetyCenterStatus.OverallSeverityLevel: " + overallSeverityLevel);
        return "";
    }

    @NonNull
    private String getRefreshErrorString(int numberOfErrorEntries) {
        return getIcuPluralsString("refresh_error", numberOfErrorEntries);
    }

    @NonNull
    private String getIcuPluralsString(String name, int count, @NonNull Object... formatArgs) {
        MessageFormat messageFormat =
                new MessageFormat(
                        mSafetyCenterResourcesContext.getStringByName(name, formatArgs),
                        Locale.getDefault());
        ArrayMap<String, Object> arguments = new ArrayMap<>();
        arguments.put("count", count);
        return messageFormat.format(arguments);
    }

    @Nullable
    private String getSafetyCenterRefreshStatusTitle(
            @SafetyCenterStatus.RefreshStatus int refreshStatus, boolean overallSeverityUnknown) {
        switch (refreshStatus) {
            case SafetyCenterStatus.REFRESH_STATUS_NONE:
                return null;
            case SafetyCenterStatus.REFRESH_STATUS_DATA_FETCH_IN_PROGRESS:
                if (!overallSeverityUnknown) {
                    return null;
                }
                // Fall through.
            case SafetyCenterStatus.REFRESH_STATUS_FULL_RESCAN_IN_PROGRESS:
                return mSafetyCenterResourcesContext.getStringByName("scanning_title");
        }

        Log.w(TAG, "Unexpected SafetyCenterStatus.RefreshStatus: " + refreshStatus);
        return null;
    }

    @Nullable
    private String getSafetyCenterRefreshStatusSummary(
            @SafetyCenterStatus.RefreshStatus int refreshStatus) {
        switch (refreshStatus) {
            case SafetyCenterStatus.REFRESH_STATUS_NONE:
                return null;
            case SafetyCenterStatus.REFRESH_STATUS_DATA_FETCH_IN_PROGRESS:
            case SafetyCenterStatus.REFRESH_STATUS_FULL_RESCAN_IN_PROGRESS:
                return mSafetyCenterResourcesContext.getStringByName("loading_summary");
        }

        Log.w(TAG, "Unexpected SafetyCenterStatus.RefreshStatus: " + refreshStatus);
        return null;
    }

    @NonNull
    private static SafetySourceKey toSafetySourceKey(@NonNull String safetyCenterEntryIdString) {
        SafetyCenterEntryId id = SafetyCenterIds.entryIdFromString(safetyCenterEntryIdString);
        return SafetySourceKey.of(id.getSafetySourceId(), id.getUserId());
    }

    /** Wrapper that encapsulates both {@link SafetyCenterIssue} and its category. */
    private static final class SafetyCenterIssueWithCategory {
        @NonNull private final SafetyCenterIssue mSafetyCenterIssue;
        @SafetySourceIssue.IssueCategory private final int mSafetyCenterIssueCategory;

        private SafetyCenterIssueWithCategory(
                @NonNull SafetyCenterIssue safetyCenterIssue,
                @SafetySourceIssue.IssueCategory int safetyCenterIssueCategory) {
            this.mSafetyCenterIssue = safetyCenterIssue;
            this.mSafetyCenterIssueCategory = safetyCenterIssueCategory;
        }

        @NonNull
        private SafetyCenterIssue getSafetyCenterIssue() {
            return mSafetyCenterIssue;
        }

        @SafetySourceIssue.IssueCategory
        private int getSafetyCenterIssueCategory() {
            return mSafetyCenterIssueCategory;
        }

        private static SafetyCenterIssueWithCategory create(
                @NonNull SafetyCenterIssue safetyCenterIssue,
                @SafetySourceIssue.IssueCategory int safetyCenterIssueCategory) {
            return new SafetyCenterIssueWithCategory(safetyCenterIssue, safetyCenterIssueCategory);
        }
    }

    /** A comparator to order {@link SafetyCenterIssueWithCategory} by severity level descending. */
    private static final class SafetyCenterIssuesBySeverityDescending
            implements Comparator<SafetyCenterIssueWithCategory> {

        private SafetyCenterIssuesBySeverityDescending() {}

        @Override
        public int compare(
                @NonNull SafetyCenterIssueWithCategory left,
                @NonNull SafetyCenterIssueWithCategory right) {
            return Integer.compare(
                    right.getSafetyCenterIssue().getSeverityLevel(),
                    left.getSafetyCenterIssue().getSeverityLevel());
        }
    }

    /**
     * An internal mutable class to keep track of the overall {@link SafetyCenterStatus} severity
     * level and whether the list of entries provided requires attention.
     */
    private static final class SafetyCenterOverallState {

        @SafetyCenterStatus.OverallSeverityLevel
        private int mIssuesOverallSeverityLevel = SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;

        @SafetyCenterStatus.OverallSeverityLevel
        private int mEntriesOverallSeverityLevel = SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK;

        /**
         * Adds a {@link SafetyCenterStatus.OverallSeverityLevel} computed from an issue.
         *
         * <p>The {@code overallSeverityLevel} provided cannot be {@link
         * SafetyCenterStatus#OVERALL_SEVERITY_LEVEL_UNKNOWN}. If the data for an issue is not
         * provided yet, this will be reflected when calling {@link
         * #addEntryOverallSeverityLevel(int)}. The exception to that are issue-only safety sources
         * but since they do not have user-visible entries they do not affect whether the overall
         * status is unknown.
         */
        private void addIssueOverallSeverityLevel(
                @SafetyCenterStatus.OverallSeverityLevel int issueOverallSeverityLevel) {
            if (issueOverallSeverityLevel == SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN) {
                return;
            }
            mIssuesOverallSeverityLevel =
                    mergeOverallSeverityLevels(
                            mIssuesOverallSeverityLevel, issueOverallSeverityLevel);
        }

        /**
         * Adds a {@link SafetyCenterStatus.OverallSeverityLevel} computed from an entry.
         *
         * <p>Entries may be unknown (e.g. due to an error or no data provided yet). In this case,
         * the overall status will be marked as unknown if there are no recommendations or critical
         * issues.
         */
        private void addEntryOverallSeverityLevel(
                @SafetyCenterStatus.OverallSeverityLevel int entryOverallSeverityLevel) {
            mEntriesOverallSeverityLevel =
                    mergeOverallSeverityLevels(
                            mEntriesOverallSeverityLevel, entryOverallSeverityLevel);
        }

        /**
         * Returns the {@link SafetyCenterStatus.OverallSeverityLevel} computed.
         *
         * <p>Returns {@link SafetyCenterStatus#OVERALL_SEVERITY_LEVEL_UNKNOWN} if any entry is
         * unknown / has errored-out and there are no recommendations or critical issues.
         *
         * <p>Otherwise, this is computed based on the maximum severity level of issues.
         */
        @SafetyCenterStatus.OverallSeverityLevel
        private int getOverallSeverityLevel() {
            if (mEntriesOverallSeverityLevel == SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN
                    && mIssuesOverallSeverityLevel
                            <= SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_OK) {
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN;
            }
            return mIssuesOverallSeverityLevel;
        }

        /**
         * Returns whether there are settings to review (i.e. at least one entry has a more severe
         * status than the overall status, or if any entry is not yet known / has errored-out).
         */
        private boolean hasSettingsToReview() {
            return mEntriesOverallSeverityLevel == SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN
                    || mEntriesOverallSeverityLevel > mIssuesOverallSeverityLevel;
        }

        @SafetyCenterStatus.OverallSeverityLevel
        private static int mergeOverallSeverityLevels(
                @SafetyCenterStatus.OverallSeverityLevel int left,
                @SafetyCenterStatus.OverallSeverityLevel int right) {
            if (left == SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN
                    || right == SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN) {
                return SafetyCenterStatus.OVERALL_SEVERITY_LEVEL_UNKNOWN;
            }
            return Math.max(left, right);
        }
    }
}

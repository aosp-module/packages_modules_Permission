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

import static com.android.permission.PermissionStatsLog.SAFETY_STATE;

import static java.util.Collections.emptyList;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.StatsManager;
import android.app.StatsManager.StatsPullAtomCallback;
import android.content.Context;
import android.safetycenter.SafetyCenterData;
import android.safetycenter.SafetySourceData;
import android.safetycenter.SafetySourceIssue;
import android.safetycenter.SafetySourceStatus;
import android.safetycenter.config.SafetySource;
import android.safetycenter.config.SafetySourcesGroup;
import android.util.Log;
import android.util.StatsEvent;

import androidx.annotation.RequiresApi;

import com.android.internal.annotations.GuardedBy;
import com.android.permission.PermissionStatsLog;
import com.android.safetycenter.internaldata.SafetyCenterIssueKey;

import java.util.List;

/**
 * A {@link StatsPullAtomCallback} that provides a {@link PermissionStatsLog#SAFETY_STATE} atom that
 * when requested by the {@link StatsManager}.
 *
 * <p>Whenever that atom, which describes the overall Safety Center, is pulled this class also
 * separately writes one {@code SAFETY_SOURCE_STATE_COLLECTED} atom for each active source (per
 * profile).
 */
@RequiresApi(TIRAMISU)
final class SafetyCenterPullAtomCallback implements StatsPullAtomCallback {

    private static final String TAG = "SafetyCenterPullAtomCal";

    @NonNull private final Context mContext;
    @NonNull private final Object mApiLock;

    @GuardedBy("mApiLock")
    @NonNull
    private final StatsdLogger mStatsdLogger;

    @GuardedBy("mApiLock")
    @NonNull
    private final SafetyCenterConfigReader mSafetyCenterConfigReader;

    @GuardedBy("mApiLock")
    @NonNull
    private final SafetyCenterRepository mSafetyCenterRepository;

    @GuardedBy("mApiLock")
    @NonNull
    private final SafetyCenterDataFactory mSafetyCenterDataFactory;

    @GuardedBy("mApiLock")
    @NonNull
    private final SafetyCenterIssueCache mSafetyCenterIssueCache;

    SafetyCenterPullAtomCallback(
            @NonNull Context context,
            @NonNull Object apiLock,
            @NonNull StatsdLogger statsdLogger,
            @NonNull SafetyCenterConfigReader safetyCenterConfigReader,
            @NonNull SafetyCenterRepository safetyCenterRepository,
            @NonNull SafetyCenterDataFactory safetyCenterDataFactory,
            @NonNull SafetyCenterIssueCache safetyCenterIssueCache) {
        mContext = context;
        mApiLock = apiLock;
        mStatsdLogger = statsdLogger;
        mSafetyCenterConfigReader = safetyCenterConfigReader;
        mSafetyCenterRepository = safetyCenterRepository;
        mSafetyCenterDataFactory = safetyCenterDataFactory;
        mSafetyCenterIssueCache = safetyCenterIssueCache;
    }

    @Override
    public int onPullAtom(int atomTag, @NonNull List<StatsEvent> statsEvents) {
        if (atomTag != SAFETY_STATE) {
            Log.w(TAG, "Attempt to pull atom: " + atomTag + ", but only SAFETY_STATE is supported");
            return StatsManager.PULL_SKIP;
        }
        if (!SafetyCenterFlags.getSafetyCenterEnabled()) {
            Log.w(TAG, "Attempt to pull SAFETY_STATE, but Safety Center is disabled");
            return StatsManager.PULL_SKIP;
        }
        List<UserProfileGroup> userProfileGroups =
                UserProfileGroup.getAllUserProfileGroups(mContext);
        synchronized (mApiLock) {
            if (!mSafetyCenterConfigReader.allowsStatsdLogging()) {
                Log.w(TAG, "Skipping pulling and writing atoms due to a test config override");
                return StatsManager.PULL_SKIP;
            }
            Log.i(TAG, "Pulling and writing atoms…");
            for (int i = 0; i < userProfileGroups.size(); i++) {
                UserProfileGroup userProfileGroup = userProfileGroups.get(i);
                statsEvents.add(createOverallSafetyStateAtomLocked(userProfileGroup));
                // The SAFETY_SOURCE_STATE_COLLECTED atoms are written instead of being pulled,
                // they do not support pull but we want to collect them at the same time as
                // the above pulled atom.
                writeSafetySourceStateCollectedAtomsLocked(userProfileGroup);
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    @GuardedBy("mApiLock")
    @NonNull
    private StatsEvent createOverallSafetyStateAtomLocked(
            @NonNull UserProfileGroup userProfileGroup) {
        SafetyCenterData safetyCenterData =
                mSafetyCenterDataFactory.getSafetyCenterData("android", userProfileGroup);
        long openIssuesCount = safetyCenterData.getIssues().size();
        long dismissedIssuesCount =
                mSafetyCenterIssueCache.countActiveIssues(userProfileGroup) - openIssuesCount;

        return mStatsdLogger.createSafetyStateEvent(
                safetyCenterData.getStatus().getSeverityLevel(),
                openIssuesCount,
                dismissedIssuesCount);
    }

    @GuardedBy("mApiLock")
    private void writeSafetySourceStateCollectedAtomsLocked(
            @NonNull UserProfileGroup userProfileGroup) {
        List<SafetySourcesGroup> safetySourcesGroups =
                mSafetyCenterConfigReader.getSafetySourcesGroups();
        for (int i = 0; i < safetySourcesGroups.size(); i++) {
            SafetySourcesGroup safetySourcesGroup = safetySourcesGroups.get(i);
            List<SafetySource> safetySources = safetySourcesGroup.getSafetySources();

            for (int j = 0; j < safetySources.size(); j++) {
                SafetySource safetySource = safetySources.get(j);

                if (!SafetySources.isExternal(safetySource) || !safetySource.isLoggingAllowed()) {
                    continue;
                }

                writeSafetySourceStateCollectedAtomLocked(
                        safetySource.getId(), userProfileGroup.getProfileParentUserId(), false);

                if (!SafetySources.supportsManagedProfiles(safetySource)) {
                    continue;
                }

                int[] managedRunningProfilesUserIds =
                        userProfileGroup.getManagedRunningProfilesUserIds();
                for (int k = 0; k < managedRunningProfilesUserIds.length; k++) {
                    writeSafetySourceStateCollectedAtomLocked(
                            safetySource.getId(), managedRunningProfilesUserIds[k], true);
                }
            }
        }
    }

    @GuardedBy("mApiLock")
    private void writeSafetySourceStateCollectedAtomLocked(
            @NonNull String safetySourceId, @UserIdInt int userId, boolean isUserManaged) {
        SafetySourceKey key = SafetySourceKey.of(safetySourceId, userId);
        SafetySourceData safetySourceData = mSafetyCenterRepository.getSafetySourceData(key);
        SafetySourceStatus safetySourceStatus =
                safetySourceData == null ? null : safetySourceData.getStatus();
        List<SafetySourceIssue> safetySourceIssues =
                safetySourceData == null ? emptyList() : safetySourceData.getIssues();
        int maxSeverityLevel = Integer.MIN_VALUE;
        long openIssuesCount = 0;
        long dismissedIssuesCount = 0;
        for (int i = 0; i < safetySourceIssues.size(); i++) {
            SafetySourceIssue safetySourceIssue = safetySourceIssues.get(i);
            SafetyCenterIssueKey safetyCenterIssueKey =
                    SafetyCenterIssueKey.newBuilder()
                            .setSafetySourceId(safetySourceId)
                            .setSafetySourceIssueId(safetySourceIssue.getId())
                            .setUserId(userId)
                            .build();

            if (mSafetyCenterIssueCache.isIssueDismissed(
                    safetyCenterIssueKey, safetySourceIssue.getSeverityLevel())) {
                dismissedIssuesCount++;
            } else {
                openIssuesCount++;
                maxSeverityLevel = Math.max(maxSeverityLevel, safetySourceIssue.getSeverityLevel());
            }
        }
        if (safetySourceStatus != null) {
            maxSeverityLevel = Math.max(maxSeverityLevel, safetySourceStatus.getSeverityLevel());
        }
        Integer maxSeverityOrNull = maxSeverityLevel > Integer.MIN_VALUE ? maxSeverityLevel : null;

        mStatsdLogger.writeSafetySourceStateCollected(
                safetySourceId,
                isUserManaged,
                maxSeverityOrNull,
                openIssuesCount,
                dismissedIssuesCount);
    }
}

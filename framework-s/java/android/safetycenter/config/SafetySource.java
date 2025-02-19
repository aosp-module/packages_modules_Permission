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

package android.safetycenter.config;

import static android.os.Build.VERSION_CODES.TIRAMISU;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.SystemApi;
import android.content.res.Resources;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.RequiresApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Data class used to represent the initial configuration of a safety source.
 *
 * @hide
 */
@SystemApi
@RequiresApi(TIRAMISU)
public final class SafetySource implements Parcelable {

    /**
     * Static safety source.
     *
     * <p>A static safety source is a source completely defined in the Safety Center configuration.
     * The source is displayed with no icon and neither the description displayed nor the tap
     * behavior can be changed at runtime. A static safety source cannot have any issue associated
     * with it.
     */
    public static final int SAFETY_SOURCE_TYPE_STATIC = 1;

    /**
     * Dynamic safety source.
     *
     * <p>The status, description, tap behavior, and related issues of a dynamic safety source can
     * be set at runtime by the package that owns the source. The source is displayed with an icon
     * reflecting the status when part of a collapsible safety sources group.
     */
    public static final int SAFETY_SOURCE_TYPE_DYNAMIC = 2;

    /**
     * Issue-only safety source.
     *
     * <p>An issue-only safety source is not displayed as an entry in the Safety Center page. The
     * package that owns an issue-only safety source can set the list of issues associated with the
     * source at runtime.
     */
    public static final int SAFETY_SOURCE_TYPE_ISSUE_ONLY = 3;

    /**
     * All possible safety source types.
     *
     * @hide
     */
    @IntDef(
            prefix = {"SAFETY_SOURCE_TYPE_"},
            value = {
                SAFETY_SOURCE_TYPE_STATIC,
                SAFETY_SOURCE_TYPE_DYNAMIC,
                SAFETY_SOURCE_TYPE_ISSUE_ONLY
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SafetySourceType {}

    /** Profile property unspecified. */
    public static final int PROFILE_NONE = 0;

    /**
     * Even when the active user has managed enabled profiles, a visible safety source will be
     * displayed as a single entry for the primary profile. For dynamic sources, refresh requests
     * will be sent to and set requests will be accepted from the primary profile only.
     */
    public static final int PROFILE_PRIMARY = 1;

    /**
     * When the user has managed enabled profiles, a visible safety source will be displayed as
     * multiple entries one for each enabled profile. For dynamic sources, refresh requests will be
     * sent to and set requests will be accepted from all profiles.
     */
    public static final int PROFILE_ALL = 2;

    /**
     * All possible profile configurations for a safety source.
     *
     * @hide
     */
    @IntDef(
            prefix = {"PROFILE_"},
            value = {PROFILE_NONE, PROFILE_PRIMARY, PROFILE_ALL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Profile {}

    /**
     * The dynamic safety source will create an enabled entry in the Safety Center page until a set
     * request is received.
     */
    public static final int INITIAL_DISPLAY_STATE_ENABLED = 0;

    /**
     * The dynamic safety source will create a disabled entry in the Safety Center page until a set
     * request is received.
     */
    public static final int INITIAL_DISPLAY_STATE_DISABLED = 1;

    /**
     * The dynamic safety source will have no entry in the Safety Center page until a set request is
     * received.
     */
    public static final int INITIAL_DISPLAY_STATE_HIDDEN = 2;

    /**
     * All possible initial display states for a dynamic safety source.
     *
     * @hide
     */
    @IntDef(
            prefix = {"INITIAL_DISPLAY_STATE_"},
            value = {
                INITIAL_DISPLAY_STATE_ENABLED,
                INITIAL_DISPLAY_STATE_DISABLED,
                INITIAL_DISPLAY_STATE_HIDDEN
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InitialDisplayState {}

    @NonNull
    public static final Creator<SafetySource> CREATOR =
            new Creator<SafetySource>() {
                @Override
                public SafetySource createFromParcel(Parcel in) {
                    int type = in.readInt();
                    return new Builder(type)
                            .setId(in.readString())
                            .setPackageName(in.readString())
                            .setTitleResId(in.readInt())
                            .setTitleForWorkResId(in.readInt())
                            .setSummaryResId(in.readInt())
                            .setIntentAction(in.readString())
                            .setProfile(in.readInt())
                            .setInitialDisplayState(in.readInt())
                            .setMaxSeverityLevel(in.readInt())
                            .setSearchTermsResId(in.readInt())
                            .setLoggingAllowed(in.readBoolean())
                            .setRefreshOnPageOpenAllowed(in.readBoolean())
                            .build();
                }

                @Override
                public SafetySource[] newArray(int size) {
                    return new SafetySource[size];
                }
            };

    @SafetySourceType private final int mType;
    @NonNull private final String mId;
    @Nullable private final String mPackageName;
    @StringRes private final int mTitleResId;
    @StringRes private final int mTitleForWorkResId;
    @StringRes private final int mSummaryResId;
    @Nullable private final String mIntentAction;
    @Profile private final int mProfile;
    @InitialDisplayState private final int mInitialDisplayState;
    private final int mMaxSeverityLevel;
    @StringRes private final int mSearchTermsResId;
    private final boolean mLoggingAllowed;
    private final boolean mRefreshOnPageOpenAllowed;

    private SafetySource(
            @SafetySourceType int type,
            @NonNull String id,
            @Nullable String packageName,
            @StringRes int titleResId,
            @StringRes int titleForWorkResId,
            @StringRes int summaryResId,
            @Nullable String intentAction,
            @Profile int profile,
            @InitialDisplayState int initialDisplayState,
            int maxSeverityLevel,
            @StringRes int searchTermsResId,
            boolean loggingAllowed,
            boolean refreshOnPageOpenAllowed) {
        mType = type;
        mId = id;
        mPackageName = packageName;
        mTitleResId = titleResId;
        mTitleForWorkResId = titleForWorkResId;
        mSummaryResId = summaryResId;
        mIntentAction = intentAction;
        mProfile = profile;
        mInitialDisplayState = initialDisplayState;
        mMaxSeverityLevel = maxSeverityLevel;
        mSearchTermsResId = searchTermsResId;
        mLoggingAllowed = loggingAllowed;
        mRefreshOnPageOpenAllowed = refreshOnPageOpenAllowed;
    }

    /** Returns the type of this safety source. */
    @SafetySourceType
    public int getType() {
        return mType;
    }

    /**
     * Returns the id of this safety source.
     *
     * <p>The id is unique among safety sources in a Safety Center configuration.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Returns the package name of this safety source.
     *
     * <p>This is the package that owns the source. The package will receive refresh requests and it
     * can send set requests for the source.
     *
     * <p>Throws an {@link UnsupportedOperationException} if the source is of type static.
     */
    @NonNull
    public String getPackageName() {
        if (mType == SAFETY_SOURCE_TYPE_STATIC) {
            throw new UnsupportedOperationException(
                    "getPackageName unsupported for static safety source");
        }
        return mPackageName;
    }

    /**
     * Returns the resource id of the title of this safety source.
     *
     * <p>The id refers to a string resource that is either accessible from any resource context or
     * that is accessible from the same resource context that was used to load the Safety Center
     * configuration. The id is {@link Resources#ID_NULL} when a title is not provided.
     *
     * <p>Throws an {@link UnsupportedOperationException} if the source is of type issue-only.
     */
    @StringRes
    public int getTitleResId() {
        if (mType == SAFETY_SOURCE_TYPE_ISSUE_ONLY) {
            throw new UnsupportedOperationException(
                    "getTitleResId unsupported for issue-only safety source");
        }
        return mTitleResId;
    }

    /**
     * Returns the resource id of the title for work of this safety source.
     *
     * <p>The id refers to a string resource that is either accessible from any resource context or
     * that is accessible from the same resource context that was used to load the Safety Center
     * configuration. The id is {@link Resources#ID_NULL} when a title for work is not provided.
     *
     * <p>Throws an {@link UnsupportedOperationException} if the source is of type issue-only or if
     * the profile property of the source is set to {@link SafetySource#PROFILE_PRIMARY}.
     */
    @StringRes
    public int getTitleForWorkResId() {
        if (mType == SAFETY_SOURCE_TYPE_ISSUE_ONLY) {
            throw new UnsupportedOperationException(
                    "getTitleForWorkResId unsupported for issue-only safety source");
        }
        if (mProfile == PROFILE_PRIMARY) {
            throw new UnsupportedOperationException(
                    "getTitleForWorkResId unsupported for primary profile safety source");
        }
        return mTitleForWorkResId;
    }

    /**
     * Returns the resource id of the summary of this safety source.
     *
     * <p>The id refers to a string resource that is either accessible from any resource context or
     * that is accessible from the same resource context that was used to load the Safety Center
     * configuration. The id is {@link Resources#ID_NULL} when a summary is not provided.
     *
     * <p>Throws an {@link UnsupportedOperationException} if the source is of type issue-only.
     */
    @StringRes
    public int getSummaryResId() {
        if (mType == SAFETY_SOURCE_TYPE_ISSUE_ONLY) {
            throw new UnsupportedOperationException(
                    "getSummaryResId unsupported for issue-only safety source");
        }
        return mSummaryResId;
    }

    /**
     * Returns the intent action of this safety source.
     *
     * <p>An intent created from the intent action should resolve to a public activity. If the
     * source is displayed as an entry in the Safety Center page, and if the action is set to {@code
     * null} or if it does not resolve to an activity the source will be marked as disabled.
     *
     * <p>Throws an {@link UnsupportedOperationException} if the source is of type issue-only.
     */
    @Nullable
    public String getIntentAction() {
        if (mType == SAFETY_SOURCE_TYPE_ISSUE_ONLY) {
            throw new UnsupportedOperationException(
                    "getIntentAction unsupported for issue-only safety source");
        }
        return mIntentAction;
    }

    /** Returns the profile property of this safety source. */
    @Profile
    public int getProfile() {
        return mProfile;
    }

    /**
     * Returns the initial display state of this safety source.
     *
     * <p>Throws an {@link UnsupportedOperationException} if the source is of type static or
     * issue-only.
     */
    @InitialDisplayState
    public int getInitialDisplayState() {
        if (mType == SAFETY_SOURCE_TYPE_STATIC) {
            throw new UnsupportedOperationException(
                    "getInitialDisplayState unsupported for static safety source");
        }
        if (mType == SAFETY_SOURCE_TYPE_ISSUE_ONLY) {
            throw new UnsupportedOperationException(
                    "getInitialDisplayState unsupported for issue-only safety source");
        }
        return mInitialDisplayState;
    }

    /**
     * Returns the maximum severity level of this safety source.
     *
     * <p>The maximum severity level dictates the maximum severity level values that can be used in
     * the source status or the source issues when setting the source data at runtime. A source can
     * always send a status severity level of at least {@link
     * android.safetycenter.SafetySourceData#SEVERITY_LEVEL_INFORMATION} even if the maximum
     * severity level is set to a lower value.
     *
     * <p>Throws an {@link UnsupportedOperationException} if the source is of type static.
     */
    public int getMaxSeverityLevel() {
        if (mType == SAFETY_SOURCE_TYPE_STATIC) {
            throw new UnsupportedOperationException(
                    "getMaxSeverityLevel unsupported for static safety source");
        }
        return mMaxSeverityLevel;
    }

    /**
     * Returns the resource id of the search terms of this safety source.
     *
     * <p>The id refers to a string resource that is either accessible from any resource context or
     * that is accessible from the same resource context that was used to load the Safety Center
     * configuration. The id is {@link Resources#ID_NULL} when search terms are not provided.
     *
     * <p>Throws an {@link UnsupportedOperationException} if the source is of type issue-only.
     */
    @StringRes
    public int getSearchTermsResId() {
        if (mType == SAFETY_SOURCE_TYPE_ISSUE_ONLY) {
            throw new UnsupportedOperationException(
                    "getSearchTermsResId unsupported for issue-only safety source");
        }
        return mSearchTermsResId;
    }

    /**
     * Returns the logging allowed property of this safety source.
     *
     * <p>Throws an {@link UnsupportedOperationException} if the source is of type static.
     */
    public boolean isLoggingAllowed() {
        if (mType == SAFETY_SOURCE_TYPE_STATIC) {
            throw new UnsupportedOperationException(
                    "isLoggingAllowed unsupported for static safety source");
        }
        return mLoggingAllowed;
    }

    /**
     * Returns the refresh on page open allowed property of this safety source.
     *
     * <p>If set to {@code true}, a refresh request will be sent to the source when the Safety
     * Center page is opened.
     *
     * <p>Throws an {@link UnsupportedOperationException} if the source is of type static.
     */
    public boolean isRefreshOnPageOpenAllowed() {
        if (mType == SAFETY_SOURCE_TYPE_STATIC) {
            throw new UnsupportedOperationException(
                    "isRefreshOnPageOpenAllowed unsupported for static safety source");
        }
        return mRefreshOnPageOpenAllowed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SafetySource)) return false;
        SafetySource that = (SafetySource) o;
        return mType == that.mType
                && Objects.equals(mId, that.mId)
                && Objects.equals(mPackageName, that.mPackageName)
                && mTitleResId == that.mTitleResId
                && mTitleForWorkResId == that.mTitleForWorkResId
                && mSummaryResId == that.mSummaryResId
                && Objects.equals(mIntentAction, that.mIntentAction)
                && mProfile == that.mProfile
                && mInitialDisplayState == that.mInitialDisplayState
                && mMaxSeverityLevel == that.mMaxSeverityLevel
                && mSearchTermsResId == that.mSearchTermsResId
                && mLoggingAllowed == that.mLoggingAllowed
                && mRefreshOnPageOpenAllowed == that.mRefreshOnPageOpenAllowed;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mType,
                mId,
                mPackageName,
                mTitleResId,
                mTitleForWorkResId,
                mSummaryResId,
                mIntentAction,
                mProfile,
                mInitialDisplayState,
                mMaxSeverityLevel,
                mSearchTermsResId,
                mLoggingAllowed,
                mRefreshOnPageOpenAllowed);
    }

    @Override
    public String toString() {
        return "SafetySource{"
                + "mType="
                + mType
                + ", mId="
                + mId
                + ", mPackageName="
                + mPackageName
                + ", mTitleResId="
                + mTitleResId
                + ", mTitleForWorkResId="
                + mTitleForWorkResId
                + ", mSummaryResId="
                + mSummaryResId
                + ", mIntentAction="
                + mIntentAction
                + ", mProfile="
                + mProfile
                + ", mInitialDisplayState="
                + mInitialDisplayState
                + ", mMaxSeverityLevel="
                + mMaxSeverityLevel
                + ", mSearchTermsResId="
                + mSearchTermsResId
                + ", mLoggingAllowed="
                + mLoggingAllowed
                + ", mRefreshOnPageOpenAllowed="
                + mRefreshOnPageOpenAllowed
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeString(mId);
        dest.writeString(mPackageName);
        dest.writeInt(mTitleResId);
        dest.writeInt(mTitleForWorkResId);
        dest.writeInt(mSummaryResId);
        dest.writeString(mIntentAction);
        dest.writeInt(mProfile);
        dest.writeInt(mInitialDisplayState);
        dest.writeInt(mMaxSeverityLevel);
        dest.writeInt(mSearchTermsResId);
        dest.writeBoolean(mLoggingAllowed);
        dest.writeBoolean(mRefreshOnPageOpenAllowed);
    }

    /** Builder class for {@link SafetySource}. */
    public static final class Builder {

        @SafetySourceType private final int mType;
        @Nullable private String mId;
        @Nullable private String mPackageName;
        @Nullable @StringRes private Integer mTitleResId;
        @Nullable @StringRes private Integer mTitleForWorkResId;
        @Nullable @StringRes private Integer mSummaryResId;
        @Nullable private String mIntentAction;
        @Nullable @Profile private Integer mProfile;
        @Nullable @InitialDisplayState private Integer mInitialDisplayState;
        @Nullable private Integer mMaxSeverityLevel;
        @Nullable @StringRes private Integer mSearchTermsResId;
        @Nullable private Boolean mLoggingAllowed;
        @Nullable private Boolean mRefreshOnPageOpenAllowed;

        /** Creates a {@link Builder} for a {@link SafetySource}. */
        public Builder(@SafetySourceType int type) {
            mType = type;
        }

        /**
         * Sets the id of this safety source.
         *
         * <p>The id must be unique among safety sources in a Safety Center configuration.
         */
        @NonNull
        public Builder setId(@Nullable String id) {
            mId = id;
            return this;
        }

        /**
         * Sets the package name of this safety source.
         *
         * <p>This is the package that owns the source. The package will receive refresh requests
         * and it can send set requests for the source.
         *
         * <p>The package name is required for sources of type dynamic and issue-only. The package
         * name is prohibited for sources of type static.
         */
        @NonNull
        public Builder setPackageName(@Nullable String packageName) {
            mPackageName = packageName;
            return this;
        }

        /**
         * Sets the resource id of the title of this safety source.
         *
         * <p>The id must refer to a string resource that is either accessible from any resource
         * context or that is accessible from the same resource context that was used to load the
         * Safety Center config. The id defaults to {@link Resources#ID_NULL} when a title is not
         * provided.
         *
         * <p>The title is required for sources of type static and for sources of type dynamic that
         * are not hidden and that do not provide search terms. The title is prohibited for sources
         * of type issue-only.
         */
        @NonNull
        public Builder setTitleResId(@StringRes int titleResId) {
            mTitleResId = titleResId;
            return this;
        }

        /**
         * Sets the resource id of the title for work of this safety source.
         *
         * <p>The id must refer to a string resource that is either accessible from any resource
         * context or that is accessible from the same resource context that was used to load the
         * Safety Center configuration. The id defaults to {@link Resources#ID_NULL} when a title
         * for work is not provided.
         *
         * <p>The title for work is required if the profile property of the source is set to {@link
         * SafetySource#PROFILE_ALL} and either the source is of type static or the source is a
         * source of type dynamic that is not hidden and that does not provide search terms. The
         * title for work is prohibited for sources of type issue-only and if the profile property
         * of the source is not set to {@link SafetySource#PROFILE_ALL}.
         */
        @NonNull
        public Builder setTitleForWorkResId(@StringRes int titleForWorkResId) {
            mTitleForWorkResId = titleForWorkResId;
            return this;
        }

        /**
         * Sets the resource id of the summary of this safety source.
         *
         * <p>The id must refer to a string resource that is either accessible from any resource
         * context or that is accessible from the same resource context that was used to load the
         * Safety Center configuration. The id defaults to {@link Resources#ID_NULL} when a summary
         * is not provided.
         *
         * <p>The summary is required for sources of type dynamic that are not hidden. The summary
         * is prohibited for sources of type issue-only.
         */
        @NonNull
        public Builder setSummaryResId(@StringRes int summaryResId) {
            mSummaryResId = summaryResId;
            return this;
        }

        /**
         * Sets the intent action of this safety source.
         *
         * <p>An intent created from the intent action should resolve to a public activity. If the
         * source is displayed as an entry in the Safety Center page, and if the action is set to
         * {@code null} or if it does not resolve to an activity the source will be marked as
         * disabled.
         *
         * <p>The intent action is required for sources of type static and for sources of type
         * dynamic that are enabled. The intent action is prohibited for sources of type issue-only.
         */
        @NonNull
        public Builder setIntentAction(@Nullable String intentAction) {
            mIntentAction = intentAction;
            return this;
        }

        /**
         * Sets the profile property of this safety source.
         *
         * <p>The profile property is explicitly required for all source types.
         */
        @NonNull
        public Builder setProfile(@Profile int profile) {
            mProfile = profile;
            return this;
        }

        /**
         * Sets the initial display state of this safety source.
         *
         * <p>The initial display state is prohibited for sources of type static and issue-only.
         */
        @NonNull
        public Builder setInitialDisplayState(@InitialDisplayState int initialDisplayState) {
            mInitialDisplayState = initialDisplayState;
            return this;
        }

        /**
         * Sets the maximum severity level of this safety source.
         *
         * <p>The maximum severity level dictates the maximum severity level values that can be used
         * in the source status or the source issues when setting the source data at runtime. A
         * source can always send a status severity level of at least {@link
         * android.safetycenter.SafetySourceData#SEVERITY_LEVEL_INFORMATION} even if the maximum
         * severity level is set to a lower value.
         *
         * <p>The maximum severity level is prohibited for sources of type static.
         */
        @NonNull
        public Builder setMaxSeverityLevel(int maxSeverityLevel) {
            mMaxSeverityLevel = maxSeverityLevel;
            return this;
        }

        /**
         * Sets the resource id of the search terms of this safety source.
         *
         * <p>The id must refer to a string resource that is either accessible from any resource
         * context or that is accessible from the same resource context that was used to load the
         * Safety Center configuration. The id defaults to {@link Resources#ID_NULL} when search
         * terms are not provided.
         *
         * <p>The search terms are prohibited for sources of type issue-only.
         */
        @NonNull
        public Builder setSearchTermsResId(@StringRes int searchTermsResId) {
            mSearchTermsResId = searchTermsResId;
            return this;
        }

        /**
         * Sets the logging allowed property of this safety source.
         *
         * <p>The logging allowed property defaults to {@code true}.
         *
         * <p>The logging allowed property is prohibited for sources of type static.
         */
        @NonNull
        public Builder setLoggingAllowed(boolean loggingAllowed) {
            mLoggingAllowed = loggingAllowed;
            return this;
        }

        /**
         * Sets the refresh on page open allowed property of this safety source.
         *
         * <p>If set to {@code true}, a refresh request will be sent to the source when the Safety
         * Center page is opened. The refresh on page open allowed property defaults to {@code
         * false}.
         *
         * <p>The refresh on page open allowed property is prohibited for sources of type static.
         */
        @NonNull
        public Builder setRefreshOnPageOpenAllowed(boolean refreshOnPageOpenAllowed) {
            mRefreshOnPageOpenAllowed = refreshOnPageOpenAllowed;
            return this;
        }

        /**
         * Creates the {@link SafetySource} defined by this {@link Builder}.
         *
         * <p>Throws an {@link IllegalStateException} if any constraint on the safety source is
         * violated.
         */
        @NonNull
        public SafetySource build() {
            if (mType != SAFETY_SOURCE_TYPE_STATIC
                    && mType != SAFETY_SOURCE_TYPE_DYNAMIC
                    && mType != SAFETY_SOURCE_TYPE_ISSUE_ONLY) {
                throw new IllegalStateException("Unexpected type");
            }
            boolean isStatic = mType == SAFETY_SOURCE_TYPE_STATIC;
            boolean isDynamic = mType == SAFETY_SOURCE_TYPE_DYNAMIC;
            boolean isIssueOnly = mType == SAFETY_SOURCE_TYPE_ISSUE_ONLY;

            BuilderUtils.validateAttribute(mId, "id", true, false);

            BuilderUtils.validateAttribute(
                    mPackageName, "packageName", isDynamic || isIssueOnly, isStatic);

            int initialDisplayState =
                    BuilderUtils.validateIntDef(
                            mInitialDisplayState,
                            "initialDisplayState",
                            false,
                            isStatic || isIssueOnly,
                            INITIAL_DISPLAY_STATE_ENABLED,
                            INITIAL_DISPLAY_STATE_ENABLED,
                            INITIAL_DISPLAY_STATE_DISABLED,
                            INITIAL_DISPLAY_STATE_HIDDEN);
            boolean isEnabled = initialDisplayState == INITIAL_DISPLAY_STATE_ENABLED;
            boolean isHidden = initialDisplayState == INITIAL_DISPLAY_STATE_HIDDEN;
            boolean isDynamicNotHidden = isDynamic && !isHidden;

            int profile =
                    BuilderUtils.validateIntDef(
                            mProfile,
                            "profile",
                            true,
                            false,
                            PROFILE_NONE,
                            PROFILE_PRIMARY,
                            PROFILE_ALL);
            boolean hasWork = profile == PROFILE_ALL;

            int searchTermsResId =
                    BuilderUtils.validateResId(
                            mSearchTermsResId, "searchTerms", false, isIssueOnly);
            boolean isDynamicHiddenWithSearch =
                    isDynamic && isHidden && searchTermsResId != Resources.ID_NULL;

            boolean titleRequired = isDynamicNotHidden || isDynamicHiddenWithSearch || isStatic;
            int titleResId =
                    BuilderUtils.validateResId(mTitleResId, "title", titleRequired, isIssueOnly);

            int titleForWorkResId =
                    BuilderUtils.validateResId(
                            mTitleForWorkResId,
                            "titleForWork",
                            hasWork && titleRequired,
                            !hasWork || isIssueOnly);

            int summaryResId =
                    BuilderUtils.validateResId(
                            mSummaryResId, "summary", isDynamicNotHidden, isIssueOnly);

            BuilderUtils.validateAttribute(
                    mIntentAction,
                    "intentAction",
                    (isDynamic && isEnabled) || isStatic,
                    isIssueOnly);

            int maxSeverityLevel =
                    BuilderUtils.validateInteger(
                            mMaxSeverityLevel,
                            "maxSeverityLevel",
                            false,
                            isStatic,
                            Integer.MAX_VALUE);

            boolean loggingAllowed =
                    BuilderUtils.validateBoolean(
                            mLoggingAllowed, "loggingAllowed", false, isStatic, true);

            boolean refreshOnPageOpenAllowed =
                    BuilderUtils.validateBoolean(
                            mRefreshOnPageOpenAllowed,
                            "refreshOnPageOpenAllowed",
                            false,
                            isStatic,
                            false);

            return new SafetySource(
                    mType,
                    mId,
                    mPackageName,
                    titleResId,
                    titleForWorkResId,
                    summaryResId,
                    mIntentAction,
                    profile,
                    initialDisplayState,
                    maxSeverityLevel,
                    searchTermsResId,
                    loggingAllowed,
                    refreshOnPageOpenAllowed);
        }
    }
}

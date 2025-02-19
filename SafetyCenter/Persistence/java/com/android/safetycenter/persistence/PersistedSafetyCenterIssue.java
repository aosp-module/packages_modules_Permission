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

package com.android.safetycenter.persistence;

import static android.os.Build.VERSION_CODES.TIRAMISU;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.time.Instant;
import java.util.Objects;

/**
 * Data class containing all the identifiers and metadata of a safety source issue that should be
 * persisted.
 */
@RequiresApi(TIRAMISU)
public final class PersistedSafetyCenterIssue {
    @NonNull private final String mKey;
    @NonNull private final Instant mFirstSeenAt;
    @Nullable private final Instant mDismissedAt;
    private final int mDismissCount;

    private PersistedSafetyCenterIssue(
            @NonNull String key,
            @NonNull Instant firstSeenAt,
            @Nullable Instant dismissedAt,
            int dismissCount) {
        mKey = key;
        mFirstSeenAt = firstSeenAt;
        mDismissedAt = dismissedAt;
        mDismissCount = dismissCount;
    }

    /** The unique key for a safety source issue. */
    @NonNull
    public String getKey() {
        return mKey;
    }

    /** The instant when this issue was first seen. */
    @NonNull
    public Instant getFirstSeenAt() {
        return mFirstSeenAt;
    }

    /** The instant when this issue was dismissed, {@code null} if the issue is not dismissed. */
    @Nullable
    public Instant getDismissedAt() {
        return mDismissedAt;
    }

    /** The number of times this issue was dismissed. */
    public int getDismissCount() {
        return mDismissCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PersistedSafetyCenterIssue)) return false;
        PersistedSafetyCenterIssue that = (PersistedSafetyCenterIssue) o;
        return Objects.equals(mKey, that.mKey)
                && Objects.equals(mFirstSeenAt, that.mFirstSeenAt)
                && Objects.equals(mDismissedAt, that.mDismissedAt)
                && mDismissCount == that.mDismissCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mKey, mFirstSeenAt, mDismissedAt, mDismissCount);
    }

    @Override
    public String toString() {
        return "PersistedSafetyCenterIssue{"
                + "mKey="
                + mKey
                + ", mFirstSeenAt="
                + mFirstSeenAt
                + ", mDismissedAt="
                + mDismissedAt
                + ", mDismissCount="
                + mDismissCount
                + '}';
    }

    /** Builder class for {@link PersistedSafetyCenterIssue}. */
    public static final class Builder {
        @Nullable private String mKey;
        @Nullable private Instant mFirstSeenAt;
        @Nullable private Instant mDismissedAt;
        private int mDismissCount = 0;

        /** Creates a {@link Builder} for a {@link PersistedSafetyCenterIssue}. */
        public Builder() {}

        /** The unique key for a safety source issue. */
        @NonNull
        public Builder setKey(@Nullable String key) {
            mKey = key;
            return this;
        }

        /** The instant when this issue was first seen. */
        @NonNull
        public Builder setFirstSeenAt(@Nullable Instant firstSeenAt) {
            mFirstSeenAt = firstSeenAt;
            return this;
        }

        /** The instant when this issue was dismissed. */
        @NonNull
        public Builder setDismissedAt(@Nullable Instant dismissedAt) {
            mDismissedAt = dismissedAt;
            return this;
        }

        /** The number of times this issue was dismissed. */
        @NonNull
        public Builder setDismissCount(int dismissCount) {
            if (dismissCount < 0) {
                throw new IllegalArgumentException("Dismiss count cannot be negative");
            }
            mDismissCount = dismissCount;
            return this;
        }

        /**
         * Creates the {@link PersistedSafetyCenterIssue} defined by this {@link Builder}.
         *
         * <p>Throws an {@link IllegalStateException} if any constraint is violated.
         */
        @NonNull
        public PersistedSafetyCenterIssue build() {
            validateRequiredAttribute(mKey, "key");
            validateRequiredAttribute(mFirstSeenAt, "firstSeenAt");

            if (mDismissedAt != null && mDismissCount == 0) {
                throw new IllegalStateException(
                        "dismissCount cannot be 0 if dismissedAt is present");
            }
            if (mDismissCount > 0 && mDismissedAt == null) {
                throw new IllegalStateException(
                        "dismissedAt must be present if dismissCount is greater than 0");
            }

            return new PersistedSafetyCenterIssue(mKey, mFirstSeenAt, mDismissedAt, mDismissCount);
        }
    }

    private static void validateRequiredAttribute(
            @Nullable Object attribute, @NonNull String name) {
        if (attribute == null) {
            throw new IllegalStateException("Required attribute " + name + " missing");
        }
    }
}

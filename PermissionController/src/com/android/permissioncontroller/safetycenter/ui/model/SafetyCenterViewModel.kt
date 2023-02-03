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

package com.android.permissioncontroller.safetycenter.ui.model

import android.app.Application
import android.os.Build
import android.safetycenter.SafetyCenterData
import android.safetycenter.SafetyCenterErrorDetails
import android.safetycenter.SafetyCenterIssue
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.android.permissioncontroller.safetycenter.ui.InteractionLogger
import com.android.permissioncontroller.safetycenter.ui.NavigationSource

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
abstract class SafetyCenterViewModel(protected val app: Application) : AndroidViewModel(app) {

    abstract val safetyCenterUiLiveData: LiveData<SafetyCenterUiData>
    abstract val errorLiveData: LiveData<SafetyCenterErrorDetails>
    abstract val interactionLogger: InteractionLogger

    abstract fun dismissIssue(issue: SafetyCenterIssue)

    abstract fun executeIssueAction(
        issue: SafetyCenterIssue,
        action: SafetyCenterIssue.Action,
        launchTaskId: Int?
    )

    /**
     * Marks a resolved [SafetyCenterIssue] as fully complete, meaning the resolution success
     * message has been shown
     *
     * @param issueId Resolved issue that has completed its UI update and view can be removed
     */
    abstract fun markIssueResolvedUiCompleted(issueId: IssueId)

    abstract fun rescan()

    abstract fun clearError()

    abstract fun navigateToSafetyCenter(
        fragment: Fragment,
        navigationSource: NavigationSource? = null
    )

    abstract fun pageOpen()

    abstract fun changingConfigurations()
}

typealias IssueId = String

typealias ActionId = String

data class SafetyCenterUiData(
    val safetyCenterData: SafetyCenterData,
    val resolvedIssues: Map<IssueId, ActionId> = emptyMap()
)

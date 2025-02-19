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
import static android.safetycenter.SafetyCenterManager.REFRESH_REASON_DEVICE_LOCALE_CHANGE;
import static android.safetycenter.SafetyCenterManager.REFRESH_REASON_DEVICE_REBOOT;
import static android.safetycenter.SafetyCenterManager.REFRESH_REASON_OTHER;
import static android.safetycenter.SafetyCenterManager.REFRESH_REASON_PAGE_OPEN;
import static android.safetycenter.SafetyCenterManager.REFRESH_REASON_RESCAN_BUTTON_CLICK;
import static android.safetycenter.SafetyCenterManager.REFRESH_REASON_SAFETY_CENTER_ENABLED;

import static java.util.Collections.unmodifiableMap;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.RemoteException;
import android.safetycenter.ISafetyCenterManager;
import android.safetycenter.SafetyCenterManager.RefreshReason;

import androidx.annotation.RequiresApi;

import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/** A {@link BasicShellCommandHandler} implementation to handle Safety Center commands. */
@RequiresApi(TIRAMISU)
final class SafetyCenterShellCommandHandler extends BasicShellCommandHandler {

    @NonNull private static final Map<String, Integer> REASONS = createReasonMap();

    @NonNull private final ISafetyCenterManager mSafetyCenterManager;

    SafetyCenterShellCommandHandler(@NonNull ISafetyCenterManager safetyCenterManager) {
        mSafetyCenterManager = safetyCenterManager;
    }

    @Override
    public int onCommand(@Nullable String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }
        try {
            // Hey! Are you adding a new command to this switch? Then don't forget to add
            // instructions for it in the onHelp function below!
            switch (cmd) {
                case "enabled":
                    return onEnabled();
                case "refresh":
                    return onRefresh();
                case "clear-data":
                    return onClearData();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException | IllegalArgumentException e) {
            e.printStackTrace(getErrPrintWriter());
            return 1;
        }
    }

    private int onEnabled() throws RemoteException {
        if (mSafetyCenterManager.isSafetyCenterEnabled()) {
            getOutPrintWriter().println("Safety Center is enabled");
            return 0;
        } else {
            getOutPrintWriter().println("Safety Center is not enabled");
            return 1;
        }
    }

    private int onRefresh() throws RemoteException {
        int reason = REFRESH_REASON_OTHER;
        int userId = 0;
        String opt = getNextOption();
        while (opt != null) {
            switch (opt) {
                case "--reason":
                    reason = parseReason();
                    break;
                case "--user":
                    userId = parseUserId();
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected option: " + opt);
            }
            opt = getNextOption();
        }
        getOutPrintWriter().println("Starting refresh…");
        mSafetyCenterManager.refreshSafetySources(reason, userId);
        return 0;
    }

    @RefreshReason
    private int parseReason() {
        String arg = getNextArgRequired();
        Integer reason = REASONS.get(arg);
        if (reason != null) {
            return reason;
        } else {
            throw new IllegalArgumentException("Invalid --reason arg: " + arg);
        }
    }

    @UserIdInt
    private int parseUserId() {
        String arg = getNextArgRequired();
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid --user arg: " + arg, e);
        }
    }

    private int onClearData() throws RemoteException {
        getOutPrintWriter().println("Clearing all data…");
        mSafetyCenterManager.clearAllSafetySourceDataForTests();
        return 0;
    }

    @Override
    public void onHelp() {
        getOutPrintWriter().println("Safety Center (safety_center) commands:");
        printCmd("help or -h", "Print this help text");
        printCmd(
                "enabled",
                "Check if Safety Center is enabled",
                "Exits with status code 0 if enabled or 1 if not enabled");
        printCmd(
                "refresh [--reason REASON] [--user USERID]",
                "Start a refresh of all sources",
                "REASON is one of "
                        + String.join(", ", REASONS.keySet())
                        + "; determines whether sources fetch fresh data (default OTHER)",
                "USERID is a user ID; refresh sources in this user profile group (default 0)");
        printCmd(
                "clear-data",
                "Clear all data held by Safety Center",
                "Includes data held in memory and persistent storage but not the listeners.");
    }

    /** Helper function to standardise pretty-printing of the help text. */
    private void printCmd(@NonNull String cmd, @NonNull String... description) {
        PrintWriter pw = getOutPrintWriter();
        pw.println("  " + cmd);
        for (int i = 0; i < description.length; i++) {
            pw.println("    " + description[i]);
        }
    }

    @NonNull
    private static Map<String, Integer> createReasonMap() {
        // LinkedHashMap so that options get printed in order
        LinkedHashMap<String, Integer> reasons = new LinkedHashMap<>(6);
        reasons.put("PAGE_OPEN", REFRESH_REASON_PAGE_OPEN);
        reasons.put("BUTTON_CLICK", REFRESH_REASON_RESCAN_BUTTON_CLICK);
        reasons.put("REBOOT", REFRESH_REASON_DEVICE_REBOOT);
        reasons.put("LOCALE_CHANGE", REFRESH_REASON_DEVICE_LOCALE_CHANGE);
        reasons.put("SAFETY_CENTER_ENABLED", REFRESH_REASON_SAFETY_CENTER_ENABLED);
        reasons.put("OTHER", REFRESH_REASON_OTHER);
        return unmodifiableMap(reasons);
    }
}

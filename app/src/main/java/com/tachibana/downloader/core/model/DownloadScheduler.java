/*
 * Copyright (C) 2018, 2019 Tachibana General Laboratories, LLC
 * Copyright (C) 2018, 2019 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * This file is part of Download Navi.
 *
 * Download Navi is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Download Navi is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Download Navi.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tachibana.downloader.core.model;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.tachibana.downloader.core.RepositoryHelper;
import com.tachibana.downloader.core.model.data.entity.DownloadInfo;
import com.tachibana.downloader.core.settings.SettingsRepository;
import com.tachibana.downloader.service.GetAndRunDownloadWorker;
import com.tachibana.downloader.service.RescheduleAllWorker;
import com.tachibana.downloader.service.RestoreDownloadsWorker;
import com.tachibana.downloader.service.RunAllWorker;
import com.tachibana.downloader.service.RunDownloadWorker;

import java.util.UUID;

public class DownloadScheduler
{
    @SuppressWarnings("unused")
    private static final String TAG = DownloadScheduler.class.getSimpleName();

    public static final String TAG_WORK_RUN_ALL_TYPE = "run_all";
    public static final String TAG_WORK_RESTORE_DOWNLOADS_TYPE = "restore_downloads";
    public static final String TAG_WORK_RUN_TYPE = "run";
    public static final String TAG_WORK_GET_AND_RUN_TYPE = "get_and_run";
    public static final String TAG_WORK_RESCHEDULE_TYPE = "reschedule";

    /*
     * Run unique work for starting download.
     * If there is existing pending (uncompleted) work, cancel it
     */

    public static void run(@NonNull Context appContext, @NonNull DownloadInfo info)
    {
        String downloadTag = getDownloadTag(info.id);
        Data data = new Data.Builder()
                .putString(RunDownloadWorker.TAG_ID, info.id.toString())
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(RunDownloadWorker.class)
                .setInputData(data)
                .setConstraints(getConstraints(appContext, info))
                .addTag(TAG_WORK_RUN_TYPE)
                .addTag(downloadTag)
                .build();
        WorkManager.getInstance(appContext).enqueueUniqueWork(downloadTag,
                ExistingWorkPolicy.REPLACE, work);
    }

    public static void run(@NonNull Context appContext, @NonNull UUID id)
    {
        Data data = new Data.Builder()
                .putString(GetAndRunDownloadWorker.TAG_ID, id.toString())
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(GetAndRunDownloadWorker.class)
                .setInputData(data)
                .addTag(TAG_WORK_GET_AND_RUN_TYPE)
                .build();
        WorkManager.getInstance(appContext).enqueue(work);
    }

    public static void undone(@NonNull Context context, @NonNull DownloadInfo info)
    {
        WorkManager.getInstance(context).cancelAllWorkByTag(getDownloadTag(info.id));
    }

    public static void rescheduleAll(@NonNull Context appContext)
    {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(RescheduleAllWorker.class)
                .addTag(TAG_WORK_RESCHEDULE_TYPE)
                .build();
        WorkManager.getInstance(appContext).enqueue(work);
    }

    public static void runAll(@NonNull Context appContext, boolean ignorePaused)
    {
        Data data = new Data.Builder()
                .putBoolean(RunAllWorker.TAG_IGNORE_PAUSED, ignorePaused)
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(RunAllWorker.class)
                .setInputData(data)
                .addTag(TAG_WORK_RUN_ALL_TYPE)
                .build();
        WorkManager.getInstance(appContext).enqueue(work);
    }

    /*
     * Run stopped (and with running status) downloads after starting app
     */

    public static void restoreDownloads(@NonNull Context appContext)
    {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(RestoreDownloadsWorker.class)
                .addTag(TAG_WORK_RESTORE_DOWNLOADS_TYPE)
                .build();
        WorkManager.getInstance(appContext).enqueue(work);
    }

    public static String getDownloadTag(UUID downloadId)
    {
        return TAG_WORK_RUN_TYPE + ":" + downloadId;
    }

    public static String extractDownloadIdFromTag(String tag)
    {
        return tag.substring(tag.indexOf(":") + 1);
    }

    private static Constraints getConstraints(Context context, DownloadInfo info)
    {
        SettingsRepository pref = RepositoryHelper.getSettingsRepository(context);

        NetworkType netType = NetworkType.CONNECTED;
        boolean onlyCharging = pref.onlyCharging();
        boolean batteryControl = pref.batteryControl();
        if (pref.enableRoaming())
            netType = NetworkType.NOT_ROAMING;
        if (info != null && info.unmeteredConnectionsOnly || pref.unmeteredConnectionsOnly())
            netType = NetworkType.UNMETERED;

        return new Constraints.Builder()
                .setRequiredNetworkType(netType)
                .setRequiresCharging(onlyCharging)
                .setRequiresBatteryNotLow(batteryControl)
                .build();
    }
}

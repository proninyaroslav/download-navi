/*
 * Copyright (C) 2019 Tachibana General Laboratories, LLC
 * Copyright (C) 2019 Yaroslav Pronin <proninyaroslav@mail.ru>
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

package com.tachibana.downloader.service;

import android.content.Context;

import com.tachibana.downloader.MainApplication;
import com.tachibana.downloader.core.RepositoryHelper;
import com.tachibana.downloader.core.model.DownloadScheduler;
import com.tachibana.downloader.core.model.data.StatusCode;
import com.tachibana.downloader.core.model.data.entity.DownloadInfo;
import com.tachibana.downloader.core.storage.DataRepository;
import com.tachibana.downloader.core.storage.DataRepositoryImpl;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/*
 * Used only by DownloadScheduler.
 */

public class RestoreDownloadsWorker extends Worker
{
    @SuppressWarnings("unused")
    private static final String TAG = RestoreDownloadsWorker.class.getSimpleName();

    public RestoreDownloadsWorker(@NonNull Context context, @NonNull WorkerParameters params)
    {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork()
    {
        Context context = getApplicationContext();
        DataRepository repo = RepositoryHelper.getDataRepository(context);

        List<DownloadInfo> infoList = repo.getAllInfo();
        if (infoList.isEmpty())
            return Result.success();

        for (DownloadInfo info : infoList) {
            if (info == null)
                continue;
            /*
             * Also restore those downloads that are incorrectly completed and
             * have the wrong status (for example, after crashing)
             */
            if (info.statusCode == StatusCode.STATUS_PENDING ||
                info.statusCode == StatusCode.STATUS_RUNNING ||
                info.statusCode == StatusCode.STATUS_FETCH_METADATA)
                DownloadScheduler.run(context, info);
        }

        return Result.success();
    }
}

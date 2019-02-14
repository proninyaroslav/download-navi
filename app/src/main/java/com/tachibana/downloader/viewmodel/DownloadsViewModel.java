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

package com.tachibana.downloader.viewmodel;

import android.app.Application;

import com.tachibana.downloader.MainApplication;
import com.tachibana.downloader.core.filter.DownloadFilter;
import com.tachibana.downloader.core.entity.DownloadInfo;
import com.tachibana.downloader.core.entity.InfoAndPieces;
import com.tachibana.downloader.core.filter.DownloadFilterCollection;
import com.tachibana.downloader.core.sorting.DownloadSorting;
import com.tachibana.downloader.core.sorting.DownloadSortingComparator;
import com.tachibana.downloader.core.storage.DataRepository;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.subjects.PublishSubject;

public class DownloadsViewModel extends AndroidViewModel
{
    private DataRepository repo;
    private DownloadSortingComparator sorting = new DownloadSortingComparator(
            new DownloadSorting(DownloadSorting.SortingColumns.none, DownloadSorting.Direction.ASC));
    private DownloadFilter categoryFilter = DownloadFilterCollection.all();
    private DownloadFilter statusFilter = DownloadFilterCollection.all();
    private DownloadFilter dateAddedFilter = DownloadFilterCollection.all();
    private PublishSubject<Boolean> forceSortAndFilter = PublishSubject.create();

    public DownloadsViewModel(@NonNull Application application)
    {
        super(application);

        repo = ((MainApplication)getApplication()).getRepository();
    }

    public Flowable<List<InfoAndPieces>> observerAllInfoAndPieces()
    {
        return repo.observeAllInfoAndPieces();
    }

    public Single<List<InfoAndPieces>> getAllInfoAndPiecesSingle()
    {
        return repo.getAllInfoAndPiecesSingle();
    }

    public Completable deleteDownload(DownloadInfo info, boolean withFile)
    {
        return Completable.fromAction(() -> repo.deleteInfo(getApplication(), info, withFile));
    }

    public Completable deleteDownloads(List<DownloadInfo> infoList, boolean withFile)
    {
        return Completable.fromAction(() -> repo.deleteInfoList(getApplication(), infoList, withFile));
    }

    public void setSort(@NonNull DownloadSortingComparator sorting, boolean force)
    {
        this.sorting = sorting;
        if (force && !sorting.getSorting().getColumnName().equals(DownloadSorting.SortingColumns.none.name()))
            forceSortAndFilter.onNext(true);
    }

    public void setCategoryFilter(@NonNull DownloadFilter categoryFilter, boolean force)
    {
        this.categoryFilter = categoryFilter;
        if (force)
            forceSortAndFilter.onNext(true);
    }

    public void setStatusFilter(@NonNull DownloadFilter statusFilter, boolean force)
    {
        this.statusFilter = statusFilter;
        if (force)
            forceSortAndFilter.onNext(true);
    }

    public void setDateAddedFilter(@NonNull DownloadFilter dateAddedFilter, boolean force)
    {
        this.dateAddedFilter = dateAddedFilter;
        if (force)
            forceSortAndFilter.onNext(true);
    }

    @NonNull
    public DownloadSortingComparator getSorting()
    {
        return sorting;
    }

    @NonNull
    public DownloadFilter getDownloadFilter()
    {
        return (infoAndPieces) -> categoryFilter.test(infoAndPieces) &&
                statusFilter.test(infoAndPieces) &&
                dateAddedFilter.test(infoAndPieces);
    }

    public Observable<Boolean> onForceSortAndFilter()
    {
        return forceSortAndFilter;
    }
}

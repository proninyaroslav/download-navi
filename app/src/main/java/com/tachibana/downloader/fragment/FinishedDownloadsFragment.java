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

package com.tachibana.downloader.fragment;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.CheckBox;

import com.tachibana.downloader.R;
import com.tachibana.downloader.adapter.DownloadItem;
import com.tachibana.downloader.adapter.DownloadListAdapter;
import com.tachibana.downloader.core.StatusCode;
import com.tachibana.downloader.core.entity.DownloadInfo;
import com.tachibana.downloader.core.utils.Utils;
import com.tachibana.downloader.dialog.BaseAlertDialog;

import java.util.Collections;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProviders;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class FinishedDownloadsFragment extends DownloadsFragment
    implements DownloadListAdapter.FinishClickListener
{
    @SuppressWarnings("unused")
    private static final String TAG = FinishedDownloadsFragment.class.getSimpleName();

    private static final String TAG_DELETE_DOWNLOAD_DIALOG = "delete_download_dialog";
    private static final String TAG_DOWNLOAD_FOR_DELETION = "download_for_deletion";

    private BaseAlertDialog deleteDownloadDialog;
    private BaseAlertDialog.SharedViewModel dialogViewModel;
    private DownloadInfo downloadForDeletion;

    public static FinishedDownloadsFragment newInstance()
    {
        FinishedDownloadsFragment fragment = new FinishedDownloadsFragment();
        Bundle args = new Bundle();
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public void onStart()
    {
        super.onStart();

        subscribeAdapter((item) ->
                StatusCode.isStatusCompleted(item.info.statusCode));
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState)
    {
        super.onActivityCreated(savedInstanceState);

        if (savedInstanceState != null)
            downloadForDeletion = savedInstanceState.getParcelable(TAG_DOWNLOAD_FOR_DELETION);

        FragmentManager fm = getFragmentManager();
        if (fm != null)
            deleteDownloadDialog = (BaseAlertDialog)fm.findFragmentByTag(TAG_DELETE_DOWNLOAD_DIALOG);
        dialogViewModel = ViewModelProviders.of(activity).get(BaseAlertDialog.SharedViewModel.class);
        Disposable d = dialogViewModel.observeEvents()
                .subscribe((event) -> {
                    if (deleteDownloadDialog == null)
                        return;
                    switch (event) {
                        case POSITIVE_BUTTON_CLICKED:
                            Dialog dialog = deleteDownloadDialog.getDialog();
                            if (dialog != null && downloadForDeletion != null) {
                                CheckBox withFile = dialog.findViewById(R.id.delete_with_file);
                                deleteDownload(downloadForDeletion, withFile.isChecked());
                            }
                        case NEGATIVE_BUTTON_CLICKED:
                            downloadForDeletion = null;
                            deleteDownloadDialog.dismiss();
                            break;
                    }
                });
        disposable.add(d);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState)
    {
        outState.putParcelable(TAG_DOWNLOAD_FOR_DELETION, downloadForDeletion);

        super.onSaveInstanceState(outState);
    }

    @Override
    public void onItemClicked(@NonNull DownloadItem item)
    {
        startActivity(Intent.createChooser(
                Utils.createOpenFileIntent(item.info),
                getString(R.string.open_using)));
    }

    @Override
    public void onItemMenuClicked(int menuId, @NonNull DownloadItem item)
    {
        switch (menuId) {
            case R.id.delete_menu:
                downloadForDeletion = item.info;
                deleteDownloadDialog();
                break;
            case R.id.open_details_menu:
                /* TODO: implement details dialog */
                break;
            case R.id.share_menu:
                shareDownload(item);
                break;
        }
    }

    private void deleteDownloadDialog()
    {
        FragmentManager fm = getFragmentManager();
        if (fm != null && fm.findFragmentByTag(TAG_DELETE_DOWNLOAD_DIALOG) == null) {
            deleteDownloadDialog = BaseAlertDialog.newInstance(
                    getString(R.string.deleting),
                    getString(R.string.delete_selected_download),
                    R.layout.dialog_delete_downloads,
                    getString(R.string.ok),
                    getString(R.string.cancel),
                    null);

            deleteDownloadDialog.show(fm, TAG_DELETE_DOWNLOAD_DIALOG);
        }
    }

    private void deleteDownload(DownloadInfo info, boolean withFile)
    {
        disposable.add(viewModel.deleteDownload(info, withFile)
                .subscribeOn(Schedulers.io())
                .subscribe());
    }

    private void shareDownload(DownloadItem item)
    {
        startActivity(Intent.createChooser(
                Utils.makeFileShareIntent(Collections.singletonList(item)),
                getString(R.string.share_via)));
    }
}

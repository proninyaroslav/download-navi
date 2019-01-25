/*
 * Copyright (C) 2016, 2018, 2019 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * This file is part of LibreTorrent.
 *
 * LibreTorrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibreTorrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LibreTorrent.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tachibana.downloader.dialog.filemanager;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tachibana.downloader.R;
import com.tachibana.downloader.adapter.filemanager.FileManagerAdapter;
import com.tachibana.downloader.adapter.filemanager.FileManagerSpinnerAdapter;
import com.tachibana.downloader.core.utils.FileUtils;
import com.tachibana.downloader.core.utils.Utils;
import com.tachibana.downloader.dialog.BaseAlertDialog;
import com.tachibana.downloader.settings.SettingsManager;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

/*
 * The simple dialog for navigation and select directory.
 */

public class FileManagerDialog extends AppCompatActivity
        implements FileManagerAdapter.ViewHolder.ClickListener
{
    @SuppressWarnings("unused")
    private static final String TAG = FileManagerDialog.class.getSimpleName();
    private static final String TAG_CUR_DIR = "cur_dir";
    private static final String TAG_LIST_FILES_STATE = "list_files_state";
    private static final String TAG_SPINNER_POS = "spinner_pos";

    private static final String TAG_INPUT_NAME_DIALOG = "input_name_dialog";
    private static final String TAG_ERR_CREATE_DIR = "err_create_dir";
    private static final String TAG_ERR_WRITE_PERM = "err_write_perm";
    private static final String TAG_ERROR_OPEN_DIR_DIALOG = "error_open_dir_dialog";
    private static final String TAG_FILE_EXISTS = "file_exists";

    public static final String TAG_CONFIG = "config";
    public static final String TAG_RETURNED_PATH = "returned_path";

    private Toolbar toolbar;
    private TextView titleCurFolderPath;
    private Spinner storageList;
    private FileManagerSpinnerAdapter storageAdapter;
    /*
     * Prevent call onItemSelected after set OnItemSelectedListener,
     * see http://stackoverflow.com/questions/21747917/undesired-onitemselected-calls/21751327#21751327
     */
    private int spinnerPos = 0;
    private RecyclerView listFiles;
    private LinearLayoutManager layoutManager;
    /* Save state scrolling */
    private Parcelable filesListState;
    private CoordinatorLayout coordinatorLayout;
    private SwipeRefreshLayout swipeRefreshLayout;
    private FileManagerAdapter adapter;
    private FloatingActionButton addFolder;
    private TextInputLayout fileNameLayout;
    private TextInputEditText fileNameEditText;
    private MediaReceiver mediaReceiver = new MediaReceiver(this);

    private String startDir;
    /* Current directory */
    private String curDir;
    private FileManagerConfig config;
    private int showMode;
    private Exception sentError;
    private BaseAlertDialog inputNameDialog;
    private BaseAlertDialog.SharedViewModel dialogViewModel;
    private CompositeDisposable disposable = new CompositeDisposable();

    /*
     * The receiver for mount and eject actions of removable storage.
     */

    public class MediaReceiver extends BroadcastReceiver
    {
        WeakReference<FileManagerDialog> dialog;

        MediaReceiver(FileManagerDialog dialog)
        {
            this.dialog = new WeakReference<>(dialog);
        }

        @Override
        public void onReceive(Context context, Intent intent)
        {
            if (dialog.get() != null)
                dialog.get().reloadSpinner();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        setTheme(Utils.getAppTheme(getApplicationContext()));
        super.onCreate(savedInstanceState);

        dialogViewModel = ViewModelProviders.of(this).get(BaseAlertDialog.SharedViewModel.class);
        inputNameDialog = (BaseAlertDialog)getSupportFragmentManager().findFragmentByTag(TAG_INPUT_NAME_DIALOG);

        Intent intent = getIntent();
        if (!intent.hasExtra(TAG_CONFIG)) {
            Log.e(TAG, "To work need to set intent with FileManagerConfig in startActivity()");

            finish();
        }

        config = intent.getParcelableExtra(TAG_CONFIG);
        showMode = config.showMode;

        setContentView(R.layout.activity_filemanager_dialog);
        Utils.showColoredStatusBar_KitKat(this);

        String title = config.title;
        toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            if (title == null || TextUtils.isEmpty(title)) {
                if (showMode == FileManagerConfig.DIR_CHOOSER_MODE)
                    toolbar.setTitle(R.string.dir_chooser_title);
                else if (showMode == FileManagerConfig.FILE_CHOOSER_MODE)
                    toolbar.setTitle(R.string.file_chooser_title);
                else if (showMode == FileManagerConfig.SAVE_FILE_MODE)
                    toolbar.setTitle(R.string.save_file);
            } else {
                toolbar.setTitle(title);
            }

            setSupportActionBar(toolbar);
        }

        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        String path = config.path;
        if (path == null || TextUtils.isEmpty(path)) {
            SharedPreferences pref = SettingsManager.getPreferences(this.getApplicationContext());
            startDir = pref.getString(getString(R.string.pref_key_filemanager_last_dir),
                                      SettingsManager.Default.fileManagerLastDir);

            if (startDir != null) {
                File dir = new File(startDir);
                if (!dir.exists() || (config.showMode == FileManagerConfig.DIR_CHOOSER_MODE ||
                                      config.showMode == FileManagerConfig.SAVE_FILE_MODE ? !dir.canWrite() : !dir.canRead()))
                    startDir = FileUtils.getDefaultDownloadPath();
            } else {
                startDir = FileUtils.getDefaultDownloadPath();
            }

        } else {
            startDir = path;
        }

        try {
            startDir = new File(startDir).getCanonicalPath();

            if (savedInstanceState != null) {
                curDir = savedInstanceState.getString(TAG_CUR_DIR);
                spinnerPos = savedInstanceState.getInt(TAG_SPINNER_POS);
            } else {
                curDir = startDir;
            }

        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }

        if (showMode == FileManagerConfig.DIR_CHOOSER_MODE) {
            addFolder = findViewById(R.id.add_fab);
            addFolder.show();
            addFolder.setOnClickListener((View v) -> {
                if (getSupportFragmentManager().findFragmentByTag(TAG_INPUT_NAME_DIALOG) == null) {
                    inputNameDialog = BaseAlertDialog.newInstance(
                            getString(R.string.dialog_new_folder_title),
                            null,
                            R.layout.dialog_text_input,
                            getString(R.string.ok),
                            getString(R.string.cancel),
                            null,
                            false);

                    inputNameDialog.show(getSupportFragmentManager(), TAG_INPUT_NAME_DIALOG);
                }
            });
        }
        if (showMode == FileManagerConfig.SAVE_FILE_MODE) {
            fileNameEditText = findViewById(R.id.file_name);
            fileNameLayout = findViewById(R.id.layout_file_name);
            fileNameLayout.setVisibility(View.VISIBLE);
            if (savedInstanceState == null)
                fileNameEditText.setText(config.fileName);
            fileNameEditText.addTextChangedListener(new TextWatcher()
            {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { /* Nothing */ }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count)
                {
                    fileNameLayout.setErrorEnabled(false);
                    fileNameLayout.setError(null);
                }

                @Override
                public void afterTextChanged(Editable s) { /* Nothing */ }
            });
        }

        listFiles = findViewById(R.id.file_list);
        layoutManager = new LinearLayoutManager(this);
        listFiles.setLayoutManager(layoutManager);
        listFiles.setItemAnimator(new DefaultItemAnimator());
        adapter = new FileManagerAdapter(config.highlightFileTypes, this);
        adapter.submitList(getChildItems(curDir));

        listFiles.setAdapter(adapter);

        coordinatorLayout = findViewById(R.id.coordinator_layout);
        swipeRefreshLayout = findViewById(R.id.swipe_container);
        swipeRefreshLayout.setColorSchemeColors(getResources().getColor(R.color.accent));
        swipeRefreshLayout.setOnRefreshListener(this::refreshDir);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            storageAdapter = new FileManagerSpinnerAdapter(this);
            storageAdapter.setTitle(curDir);
            storageAdapter.addItems(getStorageList());
            storageList = findViewById(R.id.storage_spinner);
            storageList.setAdapter(storageAdapter);
            storageList.setTag(spinnerPos);
            storageList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
            {
                @Override
                public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l)
                {
                    if (((Integer)storageList.getTag()) == i)
                        return;

                    spinnerPos = i;
                    storageList.setTag(i);
                    curDir = storageAdapter.getItem(i);
                    reloadData();
                }

                @Override
                public void onNothingSelected(AdapterView<?> adapterView)
                {
                /* Nothing */
                }
            });

            registerMediaReceiver();

        } else {
            titleCurFolderPath = findViewById(R.id.title_cur_folder_path);
            titleCurFolderPath.setText(curDir);
        }
    }

    @Override
    protected void onStop()
    {
        super.onStop();

        disposable.clear();
    }

    @Override
    protected void onStart()
    {
        super.onStart();

        subscribeAlertDialog();
    }

    private void subscribeAlertDialog()
    {
        Disposable d = dialogViewModel.observeEvents().subscribe(this::handleAlertDialogEvent);
        disposable.add(d);
    }

    private void handleAlertDialogEvent(BaseAlertDialog.Event event)
    {
        if (inputNameDialog == null)
            return;
        switch (event) {
            case POSITIVE_BUTTON_CLICKED:
                Dialog dialog = inputNameDialog.getDialog();
                if (dialog != null) {
                    EditText nameField = dialog.findViewById(R.id.text_input_dialog);
                    String name = nameField.getText().toString();

                    if (!TextUtils.isEmpty(name)) {
                        if (!createDirectory(curDir + File.separator + name))
                            showCreateFolderErrDialog();
                        else if (chooseDirectory(curDir + File.separator + name))
                            reloadData();
                    }
                }
            case NEGATIVE_BUTTON_CLICKED:
                inputNameDialog.dismiss();
                break;
        }
    }

    private void showCreateFolderErrDialog()
    {
        if (getSupportFragmentManager().findFragmentByTag(TAG_ERR_CREATE_DIR) == null) {
            BaseAlertDialog errDialog = BaseAlertDialog.newInstance(
                    getString(R.string.error),
                    getString(R.string.error_dialog_new_folder),
                    0,
                    getString(R.string.ok),
                    null,
                    null,
                    true);

            errDialog.show(getSupportFragmentManager(), TAG_ERR_CREATE_DIR);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState)
    {
        outState.putString(TAG_CUR_DIR, curDir);

        filesListState = layoutManager.onSaveInstanceState();
        outState.putParcelable(TAG_LIST_FILES_STATE, filesListState);
        outState.putInt(TAG_SPINNER_POS, spinnerPos);

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState)
    {
        super.onRestoreInstanceState(savedInstanceState);

        if (savedInstanceState != null)
            filesListState = savedInstanceState.getParcelable(TAG_LIST_FILES_STATE);
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            unregisterMediaReceiver();
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        if (filesListState != null)
            layoutManager.onRestoreInstanceState(filesListState);
    }

    private void registerMediaReceiver()
    {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addDataScheme("file");
        registerReceiver(mediaReceiver, filter);
    }

    private void unregisterMediaReceiver()
    {
        try {
            unregisterReceiver(mediaReceiver);
        } catch (IllegalArgumentException e) {
            /* Ignore non-registered receiver */
        }
    }

    @Override
    public void onItemClicked(String objectName, int objectType)
    {
        if (objectName.equals(FileManagerNode.PARENT_DIR)) {
            backToParent();

            return;
        }

        if (objectType == FileManagerNode.Type.DIR && chooseDirectory(curDir + File.separator + objectName)) {
            reloadData();

        } else if (objectType == FileManagerNode.Type.FILE
                   && showMode == FileManagerConfig.FILE_CHOOSER_MODE) {
            saveLastDirPath();

            Intent i = new Intent();
            i.putExtra(TAG_RETURNED_PATH, curDir + File.separator + objectName);
            setResult(RESULT_OK, i);
            finish();
        }
    }

    private boolean chooseDirectory(String dir)
    {
        File dirFile = new File(dir);
        if (!dirFile.exists() || !dirFile.isDirectory()) {
            dir = startDir;

        } else if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)) {
            if ((config.showMode == FileManagerConfig.DIR_CHOOSER_MODE ||
                config.showMode == FileManagerConfig.SAVE_FILE_MODE) && !dirFile.canWrite()) {
                Snackbar.make(coordinatorLayout,
                        R.string.permission_denied,
                        Snackbar.LENGTH_SHORT)
                        .show();

                return false;
            }
        }

        try {
            dir = new File(dir).getCanonicalPath();
        }
        catch (IOException e) {
            sentError = e;

            Log.e(TAG, Log.getStackTraceString(e));

            /* TODO: add error report dialog */
//            if (getSupportFragmentManager().findFragmentByTag(TAG_ERROR_OPEN_DIR_DIALOG) == null) {
//                ErrorReportAlertDialog errDialog = ErrorReportAlertDialog.newInstance(
//                        getApplicationContext(),
//                        getString(R.string.error),
//                        getString(R.string.error_open_dir),
//                        Log.getStackTraceString(e));
//
//                errDialog.show(getSupportFragmentManager(), TAG_ERROR_OPEN_DIR_DIALOG);
//            }

            return false;
        }

        curDir = dir;

        return true;
    }

    /*
     * Get subfolders or files.
     */

    private List<FileManagerNode> getChildItems(String dir)
    {
        List<FileManagerNode> items = new ArrayList<>();

        try {
            File dirFile = new File(dir);
            if (!dirFile.exists() || !dirFile.isDirectory())
                return items;

            /* Adding parent dir for navigation */
            if (!curDir.equals(FileManagerNode.ROOT_DIR))
                items.add(0, new FileManagerNode(FileManagerNode.PARENT_DIR, FileNode.Type.DIR, true));

            File[] files = dirFile.listFiles();
            if (files == null)
                return items;
            for (File file : files) {
                if (file.isDirectory())
                    items.add(new FileManagerNode(file.getName(), FileNode.Type.DIR, true));
                else
                    items.add(new FileManagerNode(file.getName(), FileManagerNode.Type.FILE,
                            showMode == FileManagerConfig.FILE_CHOOSER_MODE));
            }

        } catch (Exception e) {
            /* Ignore */
        }

        return items;
    }

    private boolean createDirectory(String name)
    {
        File newDirFile = new File(name);

        return !newDirFile.exists() && newDirFile.mkdir();
    }

    /*
     * Navigate back to an upper directory.
     */

    private void backToParent()
    {
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)) {
            File parentDir = new File(curDir).getParentFile();

            if ((config.showMode == FileManagerConfig.DIR_CHOOSER_MODE ||
                 config.showMode == FileManagerConfig.SAVE_FILE_MODE) && !parentDir.canWrite()) {
                Snackbar.make(coordinatorLayout,
                        R.string.permission_denied,
                        Snackbar.LENGTH_SHORT)
                        .show();

                return;
            }
        }

        curDir = new File(curDir).getParent();

        reloadData();
    }

    private void refreshDir()
    {
        swipeRefreshLayout.setRefreshing(true);
        reloadData();
        swipeRefreshLayout.setRefreshing(false);
    }

    private final synchronized void reloadData()
    {
        if (adapter == null)
            return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            if (storageAdapter != null)
                storageAdapter.setTitle(curDir);
        else
            titleCurFolderPath.setText(curDir);

        adapter.submitList(getChildItems(curDir));
    }

    private ArrayList<FileManagerSpinnerAdapter.StorageSpinnerItem> getStorageList()
    {
        ArrayList<FileManagerSpinnerAdapter.StorageSpinnerItem> items = new ArrayList<>();
        ArrayList<String> storageList = FileUtils.getStorageList(getApplicationContext());

        if (!storageList.isEmpty()) {
            String primaryStorage = storageList.get(0);

            items.add(new FileManagerSpinnerAdapter.StorageSpinnerItem(getString(R.string.internal_storage_name),
                    primaryStorage,
                    getFreeSpace(primaryStorage)));

            for (int i = 1; i < storageList.size(); i++) {
                String template = getString(R.string.external_storage_name);
                items.add(new FileManagerSpinnerAdapter.StorageSpinnerItem(String.format(template, i),
                        storageList.get(i),
                        getFreeSpace(storageList.get(i))));
            }
        }

        return items;
    }

    private long getFreeSpace(String path)
    {
        long availBytes = -1L;
        try {
            ParcelFileDescriptor pfd = getContentResolver()
                    .openFileDescriptor(Uri.fromFile(new File(path)), "r");
            availBytes = FileUtils.getAvailableBytes(pfd.getFileDescriptor());

        } catch (Exception e) {
            Log.e(TAG, "path= " + path + ", " + Log.getStackTraceString(e));
        }

        return availBytes;
    }

    final synchronized void reloadSpinner()
    {
        if (storageAdapter == null || adapter == null)
            return;

        storageAdapter.clear();
        storageAdapter.addItems(getStorageList());
        storageAdapter.setTitle(curDir);
        storageAdapter.notifyDataSetChanged();

        adapter.submitList(getChildItems(curDir));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.filemanager, menu);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu)
    {
        super.onPrepareOptionsMenu(menu);

        if (showMode == FileManagerConfig.FILE_CHOOSER_MODE)
            menu.findItem(R.id.filemanager_ok_menu).setVisible(false);

        return true;
    }

    private void saveLastDirPath()
    {
        SharedPreferences pref = SettingsManager.getPreferences(this.getApplicationContext());

        String keyFileManagerLastDir = getString(R.string.pref_key_filemanager_last_dir);
        if (curDir != null && !pref.getString(keyFileManagerLastDir, "").equals(curDir))
            pref.edit().putString(keyFileManagerLastDir, curDir).apply();
    }

    @Override
    public void onBackPressed()
    {
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                break;
            case R.id.filemanager_home_menu:
                String path = "";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                    if (storageList != null)
                        path = storageList.getSelectedItem().toString();
                else
                    path = FileUtils.getUserDirPath();

                if (path != null && !TextUtils.isEmpty(path)) {
                    curDir = path;
                    reloadData();
                } else {
                    if (getSupportFragmentManager().findFragmentByTag(TAG_ERROR_OPEN_DIR_DIALOG) == null) {
                        BaseAlertDialog errDialog = BaseAlertDialog.newInstance(
                                getString(R.string.error),
                                getString(R.string.error_open_dir),
                                0,
                                getString(R.string.ok),
                                null,
                                null,
                                true);

                        errDialog.show(getSupportFragmentManager(), TAG_ERROR_OPEN_DIR_DIALOG);
                    }
                }
                break;
            case R.id.filemanager_ok_menu:
                String returnPath = curDir;
                File dir = new File(curDir);
                if ((config.showMode == FileManagerConfig.DIR_CHOOSER_MODE ||
                     config.showMode == FileManagerConfig.SAVE_FILE_MODE ? !dir.canWrite() : !dir.canRead())) {
                    if (getSupportFragmentManager().findFragmentByTag(TAG_ERR_WRITE_PERM) == null) {
                        BaseAlertDialog errDialog = BaseAlertDialog.newInstance(
                                getString(R.string.error),
                                getString(R.string.error_perm_write_in_folder),
                                0,
                                getString(R.string.ok),
                                null,
                                null,
                                true);

                        errDialog.show(getSupportFragmentManager(), TAG_ERR_WRITE_PERM);
                    }

                    return true;
                }
                if (config.showMode == FileManagerConfig.SAVE_FILE_MODE) {
                    if (!checkFileNameField())
                        return true;

                    returnPath = curDir + File.separator + fileNameEditText.getText().toString();
                    if (new File(returnPath).exists()) {
                        if (getSupportFragmentManager().findFragmentByTag(TAG_FILE_EXISTS) == null) {
                            BaseAlertDialog errDialog = BaseAlertDialog.newInstance(
                                    getString(R.string.error),
                                    getString(R.string.error_file_exists),
                                    0,
                                    getString(R.string.ok),
                                    null,
                                    null,
                                    true);

                            errDialog.show(getSupportFragmentManager(), TAG_FILE_EXISTS);
                        }

                        return true;
                    }
                }

                Intent i = new Intent();
                i.putExtra(TAG_RETURNED_PATH, returnPath);
                setResult(RESULT_OK, i);
                finish();
                break;
        }

        return true;
    }

    private boolean checkFileNameField()
    {
        if (fileNameEditText == null || fileNameLayout == null)
            return false;

        if (TextUtils.isEmpty(fileNameEditText.getText())) {
            fileNameLayout.setErrorEnabled(true);
            fileNameLayout.setError(getString(R.string.file_name_is_empty));
            fileNameLayout.requestFocus();

            return false;
        }

        fileNameLayout.setErrorEnabled(false);
        fileNameLayout.setError(null);

        return true;
    }
}

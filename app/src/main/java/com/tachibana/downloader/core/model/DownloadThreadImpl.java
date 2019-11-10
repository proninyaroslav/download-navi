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
import android.net.NetworkInfo;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.tachibana.downloader.core.HttpConnection;
import com.tachibana.downloader.core.model.data.DownloadResult;
import com.tachibana.downloader.core.model.data.StatusCode;
import com.tachibana.downloader.core.model.data.entity.DownloadInfo;
import com.tachibana.downloader.core.model.data.entity.DownloadPiece;
import com.tachibana.downloader.core.model.data.entity.Header;
import com.tachibana.downloader.core.settings.SettingsRepository;
import com.tachibana.downloader.core.storage.DataRepository;
import com.tachibana.downloader.core.system.SystemFacade;
import com.tachibana.downloader.core.system.filesystem.FileDescriptorWrapper;
import com.tachibana.downloader.core.system.filesystem.FileSystemFacade;
import com.tachibana.downloader.core.utils.MimeTypeUtils;
import com.tachibana.downloader.core.utils.Utils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_BAD_REQUEST;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_CANNOT_RESUME;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_FETCH_METADATA;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_FILE_ERROR;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_HTTP_DATA_ERROR;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_PAUSED;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_RUNNING;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_STOPPED;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_SUCCESS;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_TOO_MANY_REDIRECTS;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_UNHANDLED_HTTP_CODE;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_UNKNOWN_ERROR;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_WAITING_FOR_NETWORK;
import static com.tachibana.downloader.core.model.data.StatusCode.STATUS_WAITING_TO_RETRY;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PRECON_FAILED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;

/*
 * Represent one task of downloading.
 */

public class DownloadThreadImpl implements DownloadThread
{
    @SuppressWarnings("unused")
    private static final String TAG = DownloadThreadImpl.class.getSimpleName();

    private DownloadInfo info;
    private UUID id;
    /* Stop and delete */
    private boolean stop;
    private boolean pause;
    private boolean running;
    private ExecutorService exec;
    private DataRepository repo;
    private SettingsRepository pref;
    private Context appContext;
    private FileSystemFacade fs;
    private SystemFacade systemFacade;
    private int networkType;

    public DownloadThreadImpl(@NonNull Context appContext,
                              @NonNull UUID id,
                              @NonNull DataRepository repo,
                              @NonNull SettingsRepository pref,
                              @NonNull FileSystemFacade fs,
                              @NonNull SystemFacade systemFacade)
    {
        this.id = id;
        this.appContext = appContext;
        this.repo = repo;
        this.pref = pref;
        this.fs = fs;
        this.systemFacade = systemFacade;
    }

    @Override
    public void requestStop()
    {
        stop = true;
        if (exec != null)
            exec.shutdownNow();
    }

    @Override
    public void requestPause()
    {
        pause = true;
        if (exec != null)
            exec.shutdownNow();
    }

    @Override
    public boolean isRunning()
    {
        return running;
    }

    @Override
    public DownloadResult call()
    {
        running = true;
        StopRequest ret;
        try {
            info = repo.getInfoById(id);
            if (info == null) {
                Log.w(TAG, "Info " + id + " is null, skipping");
                return new DownloadResult(id, DownloadResult.Status.STOPPED);
            }

            if (info.statusCode == STATUS_SUCCESS) {
                Log.w(TAG, id + " already finished, skipping");
                return new DownloadResult(id, DownloadResult.Status.FINISHED);
            }

            if (!info.hasMetadata)
                info.statusCode = STATUS_FETCH_METADATA;
            else
                info.statusCode = STATUS_RUNNING;
            info.statusMsg = null;
            writeToDatabase();
            /*
             * Remember which network this download started on;
             * used to determine if errors were due to network changes
             */
            NetworkInfo netInfo = systemFacade.getActiveNetworkInfo();
            if (netInfo != null)
                networkType = netInfo.getType();

            if ((ret = execDownload()) != null) {
                info.statusCode = ret.getFinalStatus();
                info.statusMsg = ret.getMessage();
                Log.i(TAG, "id=" + id + ", code=" + info.statusCode + ", msg=" + info.statusMsg);
            } else {
                info.statusCode = STATUS_SUCCESS;
            }

            checkPiecesStatus();

        } catch (Throwable t) {
            Log.e(TAG, Log.getStackTraceString(t));
            if (info != null) {
                info.statusCode = STATUS_UNKNOWN_ERROR;
                info.statusMsg = t.getMessage();
            }

        } finally {
            finalizeThread();
        }

        DownloadResult.Status status = DownloadResult.Status.FINISHED;
        if (info != null) {
            switch (info.statusCode) {
                case STATUS_PAUSED:
                    status = DownloadResult.Status.PAUSED;
                    break;
                case STATUS_STOPPED:
                    status = DownloadResult.Status.STOPPED;
                    break;
            }
        }

        return new DownloadResult(id, status);
    }

    private void finalizeThread()
    {
        if (info != null) {
            writeToDatabase();

            boolean deletePref = pref.deleteFileIfError();
            if (StatusCode.isStatusError(info.statusCode) && deletePref) {
                /* When error, free up any disk space */
                Uri filePath = fs.getFileUri(info.dirPath, info.fileName);
                if (filePath != null) {
                    try {
                        fs.deleteFile(filePath);

                    } catch (Exception e) {
                        /* Ignore */
                    }
                }
            }
        }

        running = false;
        stop = false;
        pause = false;
    }

    private void checkPiecesStatus()
    {
        List<DownloadPiece> pieces = repo.getPiecesByIdSorted(id);
        if (pieces == null || pieces.isEmpty()) {
            String errMsg = "Download deleted or missing";
            info.statusCode = STATUS_STOPPED;
            info.statusMsg = errMsg;
            Log.i(TAG, "id=" + id + ", " + errMsg);

        } else if (pieces.size() != info.getNumPieces()) {
            String errMsg = "Some pieces are missing";
            info.statusCode = STATUS_UNKNOWN_ERROR;
            info.statusMsg = errMsg;
            Log.i(TAG, "id=" + id + ", " + errMsg);

        } else {
            /* If we just finished a chunked file, record total size */
            if (pieces.size() == 1) {
                DownloadPiece piece = pieces.get(0);
                if (info.totalBytes == -1 && StatusCode.isStatusSuccess(piece.statusCode))
                    info.totalBytes = piece.curBytes;
            }

            /* Check pieces status if we are not cancelled or paused */
            StopRequest ret;
            if ((ret = checkPauseStop()) != null) {
                info.statusCode = ret.getFinalStatus();
            } else {
                boolean retry = false;
                /*
                 * Flag indicating if we've made forward progress transferring file data
                 * from a remote server.
                 */
                boolean madeProgress = false;

                for (DownloadPiece piece : pieces) {
                    /* Some errors should be retryable, unless we fail too many times */
                    if (Utils.isStatusRetryable(piece.statusCode)) {
                        retry = true;
                        madeProgress = info.getDownloadedBytes(piece) > 0;
                        break;
                    }

                    /* TODO: maybe change handle status behaviour */
                    boolean replaceStatus = StatusCode.isStatusError(piece.statusCode) &&
                            piece.statusCode > info.statusCode ||
                            piece.statusCode == StatusCode.STATUS_WAITING_FOR_NETWORK ||
                            piece.statusCode == StatusCode.STATUS_WAITING_TO_RETRY;

                    if (replaceStatus) {
                        info.statusCode = piece.statusCode;
                        info.statusMsg = piece.statusMsg;
                        break;
                    }
                }

                if (retry)
                    handleRetryableStatus(madeProgress);
            }
        }
    }

    private void handleRetryableStatus(boolean madeProgress)
    {
        info.numFailed++;

        if (info.numFailed < pref.maxDownloadRetries()) {
            NetworkInfo netInfo = systemFacade.getActiveNetworkInfo();
            if (netInfo != null && netInfo.getType() == networkType && netInfo.isConnected())
                /* Underlying network is still intact, use normal backoff */
                info.statusCode = STATUS_WAITING_TO_RETRY;
            else
                /* Network changed, retry on any next available */
                info.statusCode = STATUS_WAITING_FOR_NETWORK;

            if (getETag(repo.getHeadersById(id)) == null && madeProgress) {
                /*
                 * However, if we wrote data and have no ETag to verify
                 * contents against later, we can't actually resume
                 */
                info.statusCode = STATUS_CANNOT_RESUME;
            }
        }
    }

    private Header getETag(List<Header> headers)
    {
        for (Header header : headers) {
            if ("ETag".equals(header.name))
                return header;
        }

        return null;
    }

    private StopRequest execDownload()
    {
        try {
            StopRequest ret;
            if ((ret = checkPauseStop()) != null)
                return ret;

            if (!info.hasMetadata) {
                if ((ret = fetchMetadata()) != null)
                    return ret;
            }

            /* Create file if doesn't exists or replace it */
            Uri filePath;
            try {
                filePath = fs.createFile(info.dirPath, info.fileName, false);

            } catch (IOException e) {
                return new StopRequest(STATUS_FILE_ERROR, e);
            }
            if (filePath == null)
                return new StopRequest(STATUS_FILE_ERROR, "Unable to create file");

            if (info.totalBytes == 0)
                return new StopRequest(STATUS_SUCCESS, "Length is zero; skipping");

            if (!Utils.checkConnectivity(appContext))
                return new StopRequest(STATUS_WAITING_FOR_NETWORK);

            /* Check free space */
            long availBytes = fs.getDirAvailableBytes(info.dirPath);
            if (availBytes != -1 && availBytes < info.totalBytes)
                return new StopRequest(StatusCode.STATUS_INSUFFICIENT_SPACE_ERROR,
                        "No space left on device");

            /* Pre-flight disk space requirements, when known */
            if (info.totalBytes > 0 && pref.preallocateDiskSpace()) {
                if ((ret = allocFileSpace(filePath)) != null)
                    return ret;
            }

            exec = (info.getNumPieces() == 1 ?
                    Executors.newSingleThreadExecutor() :
                    Executors.newFixedThreadPool(info.getNumPieces()));

            ArrayList<PieceThread> pieceThreads = new ArrayList<>(info.getNumPieces());
            for (int i = 0; i < info.getNumPieces(); i++)
                pieceThreads.add(new PieceThreadImpl(appContext, id, i, repo, fs));

            /* Wait all threads */
            exec.invokeAll(pieceThreads);

        } catch (InterruptedException e) {
            requestStop();
        }

        return null;
    }

    private StopRequest fetchMetadata()
    {
        final StopRequest[] ret = new StopRequest[1];

        HttpConnection connection;
        try {
            connection = new HttpConnection(info.url);

        } catch (MalformedURLException e) {
            return new StopRequest(STATUS_BAD_REQUEST, "bad url " + info.url, e);
        } catch (GeneralSecurityException e) {
            return new StopRequest(STATUS_UNKNOWN_ERROR, "Unable to create SSLContext");
        }

        connection.setListener(new HttpConnection.Listener() {
            @Override
            public void onConnectionCreated(HttpURLConnection conn)
            {
                /* Nothing */
            }

            @Override
            public void onResponseHandle(HttpURLConnection conn, int code, String message)
            {
                switch (code) {
                    case HTTP_OK:
                        ret[0] = parseOkHeaders(conn);
                        break;
                    case HTTP_PRECON_FAILED:
                        ret[0] = new StopRequest(STATUS_CANNOT_RESUME,
                                "Precondition failed");
                        break;
                    case HTTP_UNAVAILABLE:
                        ret[0] = new StopRequest(HTTP_UNAVAILABLE, message);
                        break;
                    case HTTP_INTERNAL_ERROR:
                        ret[0] = new StopRequest(HTTP_INTERNAL_ERROR, message);
                        break;
                    default:
                        ret[0] = StopRequest.getUnhandledHttpError(code, message);
                        break;
                }
            }

            @Override
            public void onMovedPermanently(String newUrl)
            {
                info.url = newUrl;
            }

            @Override
            public void onIOException(IOException e)
            {
                if (e instanceof ProtocolException && e.getMessage().startsWith("Unexpected status line"))
                    ret[0] = new StopRequest(STATUS_UNHANDLED_HTTP_CODE, e);
                else if (e instanceof InterruptedIOException)
                    ret[0] = new StopRequest(STATUS_STOPPED, "Download cancelled");
                else
                    /* Trouble with low-level sockets */
                    ret[0] = new StopRequest(STATUS_HTTP_DATA_ERROR, e);
            }

            @Override
            public void onTooManyRedirects()
            {
                ret[0] = new StopRequest(STATUS_TOO_MANY_REDIRECTS, "Too many redirects");
            }
        });
        connection.run();

        return ret[0];
    }

    private StopRequest parseOkHeaders(HttpURLConnection conn)
    {
        String mimeType = MimeTypeUtils.normalizeMimeType(conn.getContentType());
        if (mimeType != null && !mimeType.equals(info.mimeType))
            info.mimeType = mimeType;

        final String transferEncoding = conn.getHeaderField("Transfer-Encoding");
        if (transferEncoding == null) {
            try {
                info.totalBytes = Long.parseLong(conn.getHeaderField("Content-Length"));

            } catch (NumberFormatException e) {
                info.totalBytes = -1;
            }
        } else {
            info.totalBytes = -1;
        }
        info.partialSupport = "bytes".equalsIgnoreCase(conn.getHeaderField("Accept-Ranges"));

        Header eTagHeader = null;
        /* Find already added ETag */
        for (Header header : repo.getHeadersById(id)) {
            if ("ETag".equals(header.name)) {
                eTagHeader = header;
                break;
            }
        }
        if (eTagHeader == null)
            eTagHeader = new Header(id, "ETag", conn.getHeaderField("ETag"));
        else
            eTagHeader.value = conn.getHeaderField("ETag");

        repo.addHeader(eTagHeader);

        info.hasMetadata = true;
        info.statusCode = STATUS_RUNNING;
        writeToDatabaseWithPieces();

        StopRequest ret;
        if ((ret = checkPauseStop()) != null)
            return ret;

        return null;
    }

    private StopRequest allocFileSpace(Uri filePath)
    {
        FileDescriptor fd = null;
        try (FileDescriptorWrapper w = fs.getFD(filePath)) {
            fd = w.open("rw");
            try {
                fs.fallocate(fd, info.totalBytes);

            } catch (InterruptedIOException e) {
                requestStop();
            } catch (IOException e) {
                /* Ignore space allocating, because it may not be supported */
                return null;
            }

        } catch (IOException e) {
            return new StopRequest(STATUS_FILE_ERROR, e);

        } finally {
            try {
                if (fd != null)
                    fd.sync();
            } catch (IOException e) {
                /* Ignore */
            }
        }

        return null;
    }

    private void writeToDatabase()
    {
        repo.updateInfo(info, false, false);
    }

    private void writeToDatabaseWithPieces()
    {
        repo.updateInfo(info, false, true);
    }

    @Override
    public StopRequest checkPauseStop()
    {
        if (pause)
            return new StopRequest(STATUS_PAUSED, "Download paused");
        else if (stop || Thread.currentThread().isInterrupted())
            return new StopRequest(STATUS_STOPPED, "Download cancelled");

        return null;
    }
}

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

package com.tachibana.downloader.core.entity;

import java.util.List;

import androidx.annotation.Nullable;
import androidx.room.Embedded;
import androidx.room.Relation;

public class InfoAndPieces
{
    @Embedded
    public DownloadInfo info;
    @Relation(parentColumn = "id", entityColumn = "infoId")
    public List<DownloadPiece> pieces;

    @Override
    public boolean equals(@Nullable Object o)
    {
        if (!(o instanceof InfoAndPieces))
            return false;

        if (o == this)
            return true;

        InfoAndPieces infoAndPieces = (InfoAndPieces)o;

        if (pieces.size() != infoAndPieces.pieces.size())
            return false;

        return info.equals(infoAndPieces.info) &&
                pieces.containsAll(infoAndPieces.pieces);
    }
}

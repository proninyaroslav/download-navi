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

package com.tachibana.downloader.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.tachibana.downloader.R;
import com.tachibana.downloader.core.utils.Utils;
import com.tachibana.downloader.viewmodel.settings.SettingsViewModel;
import com.takisoft.preferencex.PreferenceFragmentCompat;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProviders;
import androidx.preference.Preference;

public class SettingsFragment extends PreferenceFragmentCompat
{
    @SuppressWarnings("unused")
    private static final String TAG = SettingsFragment.class.getSimpleName();

    private static final String AppearanceSettings = "AppearanceSettingsFragment";

    private AppCompatActivity activity;
    private SettingsViewModel viewModel;

    public static SettingsFragment newInstance()
    {
        SettingsFragment fragment = new SettingsFragment();

        fragment.setArguments(new Bundle());

        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context)
    {
        super.onAttach(context);

        if (context instanceof AppCompatActivity)
            activity = (AppCompatActivity)context;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState)
    {
        super.onActivityCreated(savedInstanceState);

        if (activity == null)
            activity = (AppCompatActivity)getActivity();

        viewModel = ViewModelProviders.of(activity).get(SettingsViewModel.class);

        if (Utils.isTwoPane(activity)) {
            Fragment f = activity.getSupportFragmentManager()
                    .findFragmentById(R.id.detail_fragment_container);
            if (f == null)
                setFragment(AppearanceSettingsFragment.newInstance(),
                        getString(R.string.pref_header_appearance));
        }

        Preference appearance = findPreference(AppearanceSettingsFragment.class.getSimpleName());
        appearance.setOnPreferenceClickListener(prefClickListener);
    }

    private Preference.OnPreferenceClickListener prefClickListener = (preference) -> {
        openPreference(preference.getKey());
        return true;
    };

    private void openPreference(String prefName)
    {
        switch (prefName) {
            case AppearanceSettings:
                if (Utils.isLargeScreenDevice(getActivity())) {
                    setFragment(AppearanceSettingsFragment.newInstance(),
                            getString(R.string.pref_header_appearance));
                } else {
                    startActivity(AppearanceSettingsFragment.class,
                            getString(R.string.pref_header_appearance));
                }
                break;
        }
    }

    @Override
    public void onCreatePreferencesFix(Bundle savedInstanceState, String rootKey)
    {
        setPreferencesFromResource(R.xml.pref_headers, rootKey);
    }

    private <F extends PreferenceFragmentCompat> void setFragment(F fragment, String title)
    {
        viewModel.detailTitleChanged.setValue(title);

        if (Utils.isLargeScreenDevice(activity)) {
            activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.detail_fragment_container, fragment)
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    .commit();
        }
    }

    private <F extends PreferenceFragmentCompat> void startActivity(Class<F> fragment, String title)
    {
        Intent i = new Intent(activity, PreferenceActivity.class);
        PreferenceActivityConfig config = new PreferenceActivityConfig(
                fragment.getSimpleName(),
                title);

        i.putExtra(PreferenceActivity.TAG_CONFIG, config);
        startActivity(i);
    }
}

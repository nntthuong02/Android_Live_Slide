package com.droid2developers.liveslider.views.fragments;

import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import com.droid2developers.liveslider.live_wallpaper.LiveWallpaperService;
import com.droid2developers.liveslider.R;
import com.google.android.material.card.MaterialCardView;

import java.util.Objects;

public class ActivateFragment extends Fragment {

    private static final String TAG = ActivateFragment.class.getSimpleName();

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_activate, container, false);

        view.findViewById(R.id.activate_button).setOnClickListener(v -> {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                new ComponentName(requireContext(), LiveWallpaperService.class))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                Log.d(TAG, "onClick: ",e);
                try {
                    startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e2) {
                    Log.d(TAG, "onClick: ",e);
                    Toast.makeText(getContext(), R.string
                            .toast_failed_launch_wallpaper_chooser, Toast.LENGTH_LONG).show();
                }
            }
        });

        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        view.getRootView().setBackgroundColor(Color.argb(153, 35, 35, 35));
    }
}

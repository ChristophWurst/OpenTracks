/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package de.dennisguse.opentracks;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.cursoradapter.widget.ResourceCursorAdapter;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;

import de.dennisguse.opentracks.content.data.MarkerColumns;
import de.dennisguse.opentracks.content.data.Track;
import de.dennisguse.opentracks.content.data.TracksColumns;
import de.dennisguse.opentracks.databinding.SearchListBinding;
import de.dennisguse.opentracks.fragments.ConfirmDeleteDialogFragment;
import de.dennisguse.opentracks.fragments.DeleteMarkerDialogFragment.DeleteMarkerCaller;
import de.dennisguse.opentracks.services.TrackRecordingServiceConnection;
import de.dennisguse.opentracks.util.ActivityUtils;
import de.dennisguse.opentracks.util.IntentUtils;
import de.dennisguse.opentracks.util.ListItemUtils;
import de.dennisguse.opentracks.util.PreferencesUtils;
import de.dennisguse.opentracks.util.StringUtils;
import de.dennisguse.opentracks.util.TrackIconUtils;
import de.dennisguse.opentracks.util.TrackUtils;

/**
 * An activity to display a list of searchable results.
 *
 * @author Rodrigo Damazio
 * <p>
 * TODO: allow to refine searchable (present searchable in context menu)
 */
public class SearchListActivity extends AbstractListActivity implements DeleteMarkerCaller, ConfirmDeleteDialogFragment.ConfirmDeleteCaller {

    private static final String TAG = SearchListActivity.class.getSimpleName();

    private SharedPreferences sharedPreferences;

    private TrackRecordingServiceConnection trackRecordingServiceConnection;

    private SearchListBinding viewBinding;

    private ResourceCursorAdapter resourceCursorAdapter;

    private boolean metricUnits = true;

    private Track.Id recordingTrackId;

    private boolean recordingTrackPaused;

    private LoaderManager.LoaderCallbacks<Cursor> loaderCallbacks;

    // Callback when an item is selected in the contextual action mode
    private final ContextualActionModeCallback contextualActionModeCallback = new ContextualActionModeCallback() {
        @Override
        public void onPrepare(Menu menu, int[] positions, long[] databaseIds, boolean showSelectAll) {
            boolean isRecording = PreferencesUtils.isRecording(recordingTrackId);
            boolean isSingleSelection = positions.length == 1;

            // TODO We do not support sharing of markers yet
            menu.findItem(R.id.list_context_menu_share).setVisible(false);

            menu.findItem(R.id.list_context_menu_edit).setVisible(isSingleSelection);
            // Disable select all, no action is available for multiple selection
            menu.findItem(R.id.list_context_menu_select_all).setVisible(false);
        }

        @Override
        public boolean onClick(int itemId, int[] positions, long[] databaseId) {
            return handleContextItem(itemId, positions, databaseId);
        }
    };

    private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener = new OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
            if (PreferencesUtils.isKey(SearchListActivity.this, R.string.stats_units_key, key)) {
                metricUnits = PreferencesUtils.isMetricUnits(SearchListActivity.this);
            }
            if (PreferencesUtils.isKey(SearchListActivity.this, R.string.recording_track_id_key, key)) {
                recordingTrackId = PreferencesUtils.getRecordingTrackId(SearchListActivity.this);
            }
            if (PreferencesUtils.isKey(SearchListActivity.this, R.string.recording_track_paused_key, key)) {
                recordingTrackPaused = PreferencesUtils.isRecordingTrackPaused(SearchListActivity.this);
            }
            if (key != null) {
                runOnUiThread(() -> resourceCursorAdapter.notifyDataSetChanged());
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);

        recordingTrackPaused = PreferencesUtils.isRecordingTrackPausedDefault(this);

        sharedPreferences = PreferencesUtils.getSharedPreferences(this);
        trackRecordingServiceConnection = new TrackRecordingServiceConnection();

        resourceCursorAdapter = new ResourceCursorAdapter(this, R.layout.list_item, null, 0) {

            @Override
            public void bindView(View view, Context context, Cursor cursor) {
                int idIndex = cursor.getColumnIndexOrThrow(TracksColumns._ID);
                int iconIndex = cursor.getColumnIndexOrThrow(TracksColumns.ICON);
                int nameIndex = cursor.getColumnIndexOrThrow(TracksColumns.NAME);
                int totalTimeIndex = cursor.getColumnIndexOrThrow(TracksColumns.TOTALTIME);
                int totalDistanceIndex = cursor.getColumnIndexOrThrow(TracksColumns.TOTALDISTANCE);
                int startTimeIndex = cursor.getColumnIndexOrThrow(TracksColumns.STARTTIME);
                int categoryIndex = cursor.getColumnIndexOrThrow(TracksColumns.CATEGORY);
                int descriptionIndex = cursor.getColumnIndexOrThrow(TracksColumns.DESCRIPTION);
                int markerCountIndex = cursor.getColumnIndexOrThrow(TracksColumns.MARKER_COUNT);

                Track.Id trackId = new Track.Id(cursor.getLong(idIndex));
                boolean isRecording = trackId.equals(recordingTrackId);
                String icon = cursor.getString(iconIndex);
                int iconId = TrackIconUtils.getIconDrawable(icon);
                String name = cursor.getString(nameIndex);
                String totalTime = StringUtils.formatElapsedTime(cursor.getLong(totalTimeIndex));
                String totalDistance = StringUtils.formatDistance(SearchListActivity.this, cursor.getDouble(totalDistanceIndex), metricUnits);
                int markerCount = cursor.getInt(markerCountIndex);
                long startTime = cursor.getLong(startTimeIndex);
                String category = icon != null && !icon.equals("") ? null : cursor.getString(categoryIndex);
                String description = cursor.getString(descriptionIndex);

                String photoUrl = "";
                int iconContentDescriptionId = R.string.image_track;
                boolean isPaused = false;
                ListItemUtils.setListItem(SearchListActivity.this, view, isRecording, isPaused, iconId,
                        iconContentDescriptionId, name, totalTime, totalDistance, markerCount,
                        startTime, false, category, description, photoUrl);
            }
        };
        // UI elements
        viewBinding.searchList.setEmptyView(viewBinding.searchListEmpty);
        viewBinding.searchList.setAdapter(resourceCursorAdapter);
        //TODO
        viewBinding.searchList.setOnItemClickListener((parent, view, position, id) -> {
//            Cursor data = (Cursor) resourceCursorAdapter.getItem(position);

            Intent intent = IntentUtils.newIntent(SearchListActivity.this, TrackRecordedActivity.class);
//            if (markerId != null) {
//                intent = intent.putExtra(TrackRecordedActivity.EXTRA_MARKER_ID, new Marker.Id(markerId));
//            } else {
            intent = intent.putExtra(TrackRecordedActivity.EXTRA_TRACK_ID, new Track.Id(id));
//            }
            startActivity(intent);
        });
        ActivityUtils.configureListViewContextualMenu(viewBinding.searchList, contextualActionModeCallback);
        handleIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
        sharedPreferenceChangeListener.onSharedPreferenceChanged(null, null);
        trackRecordingServiceConnection.startConnection(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LoaderManager.getInstance(this).restartLoader(0, null, loaderCallbacks);
    }

    @Override
    protected void onStop() {
        super.onStop();
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
        trackRecordingServiceConnection.unbind(this);
    }

    @Override
    protected View getRootView() {
        viewBinding = SearchListBinding.inflate(getLayoutInflater());
        return viewBinding.getRoot();
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Handles a context item selection.
     *
     * @param itemId    the menu item id
     * @param positions the positions of the selected rows
     * @return true if handled.
     */
    private boolean handleContextItem(int itemId, int[] positions, long[] databaseIds) {
        Cursor cursor = (Cursor) resourceCursorAdapter.getItem(positions[0]);
        Intent intent;
        switch (itemId) {
            case R.id.list_context_menu_show_on_map:
                //TODO Support tracks and markers
//                IntentDashboardUtils.startDashboard(this, false, trackIds);
//                IntentUtils.showCoordinateOnMap(this, (double) item.get(MARKER_LATITUDE_FIELD), (double) item.get(MARKER_LONGITUDE_FIELD), item.get(NAME_FIELD) + "");
                return true;
            case R.id.list_context_menu_share:
                // TODO Not supported for markers
//                intent = IntentUtils.newShareFileIntent(this, trackId);
//                intent = Intent.createChooser(intent, null);
//                startActivity(intent);
                return true;
            case R.id.list_context_menu_edit:
//                if (markerId != null) {
//                    intent = IntentUtils.newIntent(this, MarkerEditActivity.class)
//                            .putExtra(MarkerEditActivity.EXTRA_MARKER_ID, markerId);
//                } else {
//                    intent = IntentUtils.newIntent(this, TrackEditActivity.class)
//                            .putExtra(TrackEditActivity.EXTRA_TRACK_ID, trackId);
//                }
//                startActivity(intent);

                //TODO Requery results
                // Close the searchable result since its content can change after edit.
                finish();
                return true;
            case R.id.list_context_menu_delete:
                //TODO We need a joint marker and track deletion dialog
//                if (markerId != null) {
//                    DeleteMarkerDialogFragment.showDialog(getSupportFragmentManager(), markerId);
//                } else {
//                    deleteTracks(trackId);
//                }
                return true;
            default:
                return false;
        }
    }

    /**
     * Handles the intent.
     *
     * @param intent the intent
     */
    private void handleIntent(Intent intent) {
        if (!Intent.ACTION_SEARCH.equals(intent.getAction())) {
            Log.e(TAG, "Invalid intent action: " + intent);
            finish();
            return;
        }

        final String textQuery = intent.getStringExtra(SearchManager.QUERY);
        setTitle(textQuery);

        doSearch(textQuery);
    }

    private void doSearch(String textQuery) {
        final String[] PROJECTION = new String[]{TracksColumns._ID, TracksColumns.NAME,
                TracksColumns.DESCRIPTION, TracksColumns.CATEGORY, TracksColumns.STARTTIME,
                TracksColumns.TOTALDISTANCE, TracksColumns.TOTALTIME, TracksColumns.ICON, TracksColumns.MARKER_COUNT};

        final String TRACK_SELECTION_QUERY =
                TracksColumns.NAME + " LIKE ? OR " +
                        TracksColumns.DESCRIPTION + " LIKE ? OR " +
                        TracksColumns.CATEGORY + " LIKE ?";

        final String MARKER_SELECTION_QUERY =
                MarkerColumns.NAME + " LIKE ? OR " +
                        MarkerColumns.DESCRIPTION + " LIKE ? OR " +
                        MarkerColumns.CATEGORY + " LIKE ?";

        String textQueryWildcard = "%" + textQuery + "%";
        final String[] selectionArgs = new String[]{textQueryWildcard, textQueryWildcard, textQueryWildcard};

        loaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
            @NonNull
            @Override
            public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
                return new CursorLoader(SearchListActivity.this, TracksColumns.CONTENT_URI, PROJECTION, TRACK_SELECTION_QUERY, selectionArgs, TrackUtils.TRACK_SORT_ORDER);
            }

            @Override
            public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
                resourceCursorAdapter.swapCursor(cursor);
            }

            @Override
            public void onLoaderReset(@NonNull Loader<Cursor> loader) {
                resourceCursorAdapter.swapCursor(null);
            }
        };
        LoaderManager.getInstance(this).initLoader(0, null, loaderCallbacks);
    }

    @Override
    public void onDeleteMarkerDone() {
        runOnUiThread(() -> handleIntent(getIntent()));
    }

    @Override
    protected TrackRecordingServiceConnection getTrackRecordingServiceConnection() {
        return trackRecordingServiceConnection;
    }

    @Override
    protected void onDeleted() {
        runOnUiThread(() -> handleIntent(getIntent()));
    }
}

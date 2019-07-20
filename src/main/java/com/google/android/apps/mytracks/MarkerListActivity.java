/*
 * Copyright 2009 Google Inc.
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

package com.google.android.apps.mytracks;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.cursoradapter.widget.ResourceCursorAdapter;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;

import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.content.Waypoint.WaypointType;
import com.google.android.apps.mytracks.content.WaypointsColumns;
import com.google.android.apps.mytracks.fragments.DeleteMarkerDialogFragment;
import com.google.android.apps.mytracks.fragments.DeleteMarkerDialogFragment.DeleteMarkerCaller;
import com.google.android.apps.mytracks.util.IntentUtils;
import com.google.android.apps.mytracks.util.ListItemUtils;
import com.google.android.apps.mytracks.util.PreferencesUtils;
import com.google.android.maps.mytracks.R;

/**
 * Activity to show a list of markers in a track.
 * 
 * @author Leif Hendrik Wilden
 */
public class MarkerListActivity extends AbstractActivity implements DeleteMarkerCaller {

  public static final String EXTRA_TRACK_ID = "track_id";

  private static final String TAG = MarkerListActivity.class.getSimpleName();

  private static final String[] PROJECTION = new String[] { WaypointsColumns._ID,
      WaypointsColumns.NAME, WaypointsColumns.DESCRIPTION, WaypointsColumns.CATEGORY,
      WaypointsColumns.TYPE, WaypointsColumns.TIME, WaypointsColumns.PHOTOURL,
      WaypointsColumns.LATITUDE, WaypointsColumns.LONGITUDE};

  // Callback when an item is selected in the contextual action mode
  private ContextualActionModeCallback contextualActionModeCallback = new ContextualActionModeCallback() {
        @Override
        public void onPrepare(Menu menu, int[] positions, long[] ids, boolean showSelectAll) {
          boolean isSingleSelection = ids.length == 1;

          menu.findItem(R.id.list_context_menu_share).setVisible(false);
          menu.findItem(R.id.list_context_menu_show_on_map).setVisible(isSingleSelection);
          menu.findItem(R.id.list_context_menu_edit).setVisible(isSingleSelection);
          menu.findItem(R.id.list_context_menu_delete).setVisible(true);
          /*
           * Set select all to the same visibility as delete since delete is the
           * only action that can be applied to multiple markers.
           */
          menu.findItem(R.id.list_context_menu_select_all).setVisible(showSelectAll);
        }

          @Override
        public boolean onClick(int itemId, int[] positions, long[] ids) {
          return handleContextItem(itemId, ids);
        }
      };

  /*
   * Note that sharedPreferenceChangeListener cannot be an anonymous inner
   * class. Anonymous inner class will get garbage collected.
   */
  private final OnSharedPreferenceChangeListener
      sharedPreferenceChangeListener = new OnSharedPreferenceChangeListener() {
          @Override
        public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
          // Note that the key can be null
          if (key == null || key.equals(PreferencesUtils.getKey(MarkerListActivity.this, R.string.recording_track_id_key))) {
            recordingTrackId = PreferencesUtils.getLong(MarkerListActivity.this, R.string.recording_track_id_key);
          }
          if (key == null || key.equals(PreferencesUtils.getKey(MarkerListActivity.this, R.string.recording_track_paused_key))) {
            recordingTrackPaused = PreferencesUtils.getBoolean(MarkerListActivity.this, R.string.recording_track_paused_key, PreferencesUtils.RECORDING_TRACK_PAUSED_DEFAULT);
          }
          if (key != null) {
            runOnUiThread(new Runnable() {
                @Override
              public void run() {
                  MarkerListActivity.this.invalidateOptionsMenu();
              }
            });
          }
        }
      };

  private MyTracksProviderUtils myTracksProviderUtils;
  private SharedPreferences sharedPreferences;

  private long recordingTrackId = PreferencesUtils.RECORDING_TRACK_ID_DEFAULT;
  private boolean recordingTrackPaused = PreferencesUtils.RECORDING_TRACK_PAUSED_DEFAULT;

  private Track track;
  private ResourceCursorAdapter resourceCursorAdapter;

  // UI elements
  private ListView listView;
  private MenuItem insertMarkerMenuItem;
  private MenuItem searchMenuItem;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    myTracksProviderUtils = MyTracksProviderUtils.Factory.get(this);
    sharedPreferences = getSharedPreferences(Constants.SETTINGS_NAME, Context.MODE_PRIVATE);

    long trackId = getIntent().getLongExtra(EXTRA_TRACK_ID, -1L);
    track = trackId != -1L ? myTracksProviderUtils.getTrack(trackId) : null;
    final long trackFirstWaypointId = trackId != -1 ? myTracksProviderUtils.getFirstWaypointId(trackId) : -1;

    setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);

    listView = findViewById(R.id.marker_list);
    listView.setEmptyView(findViewById(R.id.marker_list_empty));
    listView.setOnItemClickListener(new OnItemClickListener() {
        @Override
      public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Intent intent = IntentUtils.newIntent(MarkerListActivity.this, MarkerDetailActivity.class)
            .putExtra(MarkerDetailActivity.EXTRA_MARKER_ID, id);
        startActivity(intent);
      }
    });
    resourceCursorAdapter = new ResourceCursorAdapter(this, R.layout.list_item, null, 0) {
        @Override
      public void bindView(View view, Context context, Cursor cursor) {
        int typeIndex = cursor.getColumnIndex(WaypointsColumns.TYPE);
        int nameIndex = cursor.getColumnIndex(WaypointsColumns.NAME);
        int timeIndex = cursor.getColumnIndexOrThrow(WaypointsColumns.TIME);
        int categoryIndex = cursor.getColumnIndex(WaypointsColumns.CATEGORY);
        int descriptionIndex = cursor.getColumnIndex(WaypointsColumns.DESCRIPTION);
        int photoUrlIndex = cursor.getColumnIndex(WaypointsColumns.PHOTOURL);
        int latitudeIndex = cursor.getColumnIndex(WaypointsColumns.LATITUDE);
        int longitudeIndex = cursor.getColumnIndex(WaypointsColumns.LONGITUDE);

        boolean statistics = WaypointType.values()[cursor.getInt(typeIndex)] == WaypointType.STATISTICS;
        int iconId = statistics ? R.drawable.ic_marker_yellow_pushpin : R.drawable.ic_marker_blue_pushpin;
        String name = cursor.getString(nameIndex);
        long time = cursor.getLong(timeIndex);
        String category = statistics ? null : cursor.getString(categoryIndex);
        String description = statistics ? null : cursor.getString(descriptionIndex);
        String photoUrl = cursor.getString(photoUrlIndex);
        double latitude = cursor.getDouble(latitudeIndex);
        double longitude = cursor.getDouble(longitudeIndex);

        ListItemUtils.setListItem(MarkerListActivity.this, view, false, true, iconId, R.string.image_marker, name, null, null, 0, time, false, category, description, photoUrl);
      }
    };
    listView.setAdapter(resourceCursorAdapter);
    AbstractTrackActivity.configureListViewContextualMenu(listView, contextualActionModeCallback);

    LoaderManager.getInstance(this).initLoader(0, null, new LoaderCallbacks<Cursor>() {
        @NonNull
        @Override
      public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
        if (track != null) {
          return new CursorLoader(MarkerListActivity.this, WaypointsColumns.CONTENT_URI, PROJECTION,
                  WaypointsColumns.TRACKID + "=? AND " + WaypointsColumns._ID + "!=?",
                  new String[] { String.valueOf(track.getId()), String.valueOf(trackFirstWaypointId) }, null);
        } else {
          return new CursorLoader(MarkerListActivity.this, WaypointsColumns.CONTENT_URI, PROJECTION,
                  WaypointsColumns.STARTTIME + " IS NULL",null, null);
        }
      }

        @Override
      public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
        resourceCursorAdapter.swapCursor(cursor);
      }

        @Override
      public void onLoaderReset(@NonNull Loader<Cursor> loader) {
        resourceCursorAdapter.swapCursor(null);
      }
    });
  }

  @Override
  protected void onStart() {
    super.onStart();
    sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    sharedPreferenceChangeListener.onSharedPreferenceChanged(null, null);
  }

  @Override
  protected void onResume() {
    super.onResume();
    this.invalidateOptionsMenu();
  }

  @Override
  protected void onStop() {
    super.onStop();
    sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
  }

  @Override
  protected int getLayoutResId() {
    return R.layout.marker_list;
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.marker_list, menu);

    insertMarkerMenuItem = menu.findItem(R.id.marker_list_insert_marker);

    searchMenuItem = menu.findItem(R.id.marker_list_search);
    AbstractTrackActivity.configureSearchWidget(this, searchMenuItem, null);

    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    insertMarkerMenuItem.setVisible(track != null && track.getId() == recordingTrackId && !recordingTrackPaused);
    return super.onPrepareOptionsMenu(menu);
  }
  
  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (track != null && item.getItemId() == R.id.marker_list_insert_marker) {
      Intent intent = IntentUtils.newIntent(this, MarkerEditActivity.class)
              .putExtra(MarkerEditActivity.EXTRA_TRACK_ID, track.getId());
      startActivity(intent);
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
    super.onCreateContextMenu(menu, v, menuInfo);
    getMenuInflater().inflate(R.menu.list_context_menu, menu);

    AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
    contextualActionModeCallback.onPrepare(menu, new int[] { info.position }, new long[] { info.id }, false);
  }

  @Override
  public boolean onContextItemSelected(MenuItem item) {
    AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
    if (handleContextItem(item.getItemId(), new long[] {info.id})) {
      return true;
    }
    return super.onContextItemSelected(item);
  }

  /**
   * Handles a context item selection.
   * 
   * @param itemId the menu item id
   * @param markerIds the marker ids
   * @return true if handled.
   */
  private boolean handleContextItem(int itemId, long[] markerIds) {
    Intent intent;
    switch (itemId) {
      case R.id.list_context_menu_show_on_map:
        if (markerIds.length == 1) {
          intent = IntentUtils.newIntent(this, TrackDetailActivity.class)
              .putExtra(TrackDetailActivity.EXTRA_MARKER_ID, markerIds[0]);
          //TODO Use IntentUtils.newShowOnMapIntent()
          startActivity(intent);
        }
        return true;
      case R.id.list_context_menu_edit:
        if (markerIds.length == 1) {
          intent = IntentUtils.newIntent(this, MarkerEditActivity.class)
              .putExtra(MarkerEditActivity.EXTRA_MARKER_ID, markerIds[0]);
          startActivity(intent);
        }
        return true;
      case R.id.list_context_menu_delete:
        if (markerIds.length > 1 && markerIds.length == listView.getCount()) {
          markerIds = new long[] { -1L };
        }
        DeleteMarkerDialogFragment.newInstance(markerIds)
            .show(getSupportFragmentManager(), DeleteMarkerDialogFragment.DELETE_MARKER_DIALOG_TAG);
        return true;
      case R.id.list_context_menu_select_all:
        int size = listView.getCount();
        for (int i = 0; i < size; i++) {
          listView.setItemChecked(i, true);
        }
        return false;
      default:
        return false;
    }
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_SEARCH && searchMenuItem != null) {
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  @Override
  public void onDeleteMarkerDone() {
    // Do nothing
  }
}
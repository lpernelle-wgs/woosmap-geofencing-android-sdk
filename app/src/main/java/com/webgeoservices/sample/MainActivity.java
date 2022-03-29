package com.webgeoservices.sample;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.urbanairship.UAirship;
import com.urbanairship.analytics.CustomEvent;
import com.webgeoservices.sample.model.PlaceData;
import com.webgeoservices.woosmapgeofencing.DistanceAPIDataModel.DistanceAPI;
import com.webgeoservices.woosmapgeofencing.FigmmForVisitsCreator;
import com.webgeoservices.woosmapgeofencing.PositionsManager;
import com.webgeoservices.woosmapgeofencing.Woosmap;
import com.webgeoservices.woosmapgeofencing.WoosmapSettings;
import com.webgeoservices.woosmapgeofencing.database.Distance;
import com.webgeoservices.woosmapgeofencing.database.MovingPosition;
import com.webgeoservices.woosmapgeofencing.database.POI;
import com.webgeoservices.woosmapgeofencing.database.Region;
import com.webgeoservices.woosmapgeofencing.database.RegionLog;
import com.webgeoservices.woosmapgeofencing.database.Visit;
import com.webgeoservices.woosmapgeofencing.database.WoosmapDb;
import com.webgeoservices.woosmapgeofencing.database.ZOI;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class MainActivity extends AppCompatActivity {
    static SimpleDateFormat displayDateFormatAirship = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");

    private static final int REQUEST_PERMISSIONS_REQUEST_CODE = 34;

    private LocationFragment locationFragment;
    private MapFragment mapFragment;

    private boolean isMenuOpen = false;

    private BottomNavigationView bottomNav;

    private Woosmap woosmap;


    @Override
    public void onStart() {
        super.onStart();

        if (!checkPermissions()) {
            requestPermissions();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (checkPermissions()) {
            Log.d("WoosmapGeofencing", "Permission OK");
            this.woosmap.onResume();
        } else {
            Log.d("WoosmapGeofencing", "Permission NOK");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d("WoosmapGeofencing", "BackGround");
        if (checkPermissions()) {
           this.woosmap.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        woosmap.onDestroy();
        super.onDestroy();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        locationFragment = new LocationFragment();
        mapFragment = new MapFragment();

        setFragment(mapFragment);
        setFragment(locationFragment);

        loadData();


        bottomNav = findViewById(R.id.bottom_navigation);
        bottomNav.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.navigation_location:
                        setFragment(locationFragment);
                        return true;
                    default:
                        return false;
                }
            }

        });

        // TODO this could maybe be remove with new profiles methods
        final SharedPreferences mPrefs = getApplicationContext().getSharedPreferences("WGSGeofencingPref",MODE_PRIVATE);


        // Instanciate woosmap object
        this.woosmap = Woosmap.getInstance().initializeWoosmap(this);

        this.woosmap.startTracking("liveTracking");

        // For android version >= 8 you have to create a channel or use the woosmap's channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.woosmap.createWoosmapNotifChannel();
        }

        this.InitializeOptionsPanel();
    }

    private void InitializeOptionsPanel() {
        final SharedPreferences mPrefs = getApplicationContext().getSharedPreferences("WGSGeofencingPref",MODE_PRIVATE);
        final SharedPreferences.Editor editor = mPrefs.edit();

        final FloatingActionButton clearDBBtn = findViewById(R.id.clearDB);
        clearDBBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Clear Database", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();

                new clearDBTask(getApplicationContext(), MainActivity.this).execute();
            }
        });


        final FloatingActionButton menuSettings = findViewById(R.id.Menu);
        menuSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(!isMenuOpen) {
                    isMenuOpen = true;
                    clearDBBtn.animate().translationY(-200);
                } else {
                    isMenuOpen = false;
                    clearDBBtn.animate().translationY(0);
                }
            }
        });

    }

    public void loadData() {
        final LiveData<MovingPosition[]> movingPositionList = WoosmapDb.getInstance(getApplicationContext()).getMovingPositionsDao().getLiveDataMovingPositions(-1);
        movingPositionList.observe(this, new Observer<MovingPosition[]>() {
            @Override
            public void onChanged(MovingPosition[] movingPositions) {
                final ArrayList<PlaceData> arrayOfPlaceData = new ArrayList<>();
                for (MovingPosition locationToShow : movingPositionList.getValue()) {
                    final PlaceData place = new PlaceData();
                    place.setType( PlaceData.dataType.location );
                    place.setLatitude( locationToShow.lat );
                    place.setLongitude( locationToShow.lng );
                    place.setDate(locationToShow.dateTime);
                    place.setLocationId( locationToShow.id );
                    arrayOfPlaceData.add( place );
                    if(place.getType() == PlaceData.dataType.location) {
                        LatLng latLng = new LatLng( place.getLatitude(), place.getLongitude() );
                        boolean markerToAdd = true;
                        MarkerOptions markerOptions = new MarkerOptions().position( latLng ).icon( BitmapDescriptorFactory.defaultMarker( BitmapDescriptorFactory.HUE_MAGENTA ) );
                        if (!MainActivity.this.mapFragment.markersLocations.isEmpty()) {
                            for (MarkerOptions marker : MainActivity.this.mapFragment.markersLocations) {
                                if (marker.getPosition().equals( markerOptions.getPosition() )) {
                                    markerToAdd = false;
                                }
                            }
                        }
                        if (markerToAdd) {
                            MainActivity.this.mapFragment.markersLocations.add( markerOptions );
                            if (MainActivity.this.mapFragment.mGoolgeMap != null && MainActivity.this.mapFragment.isVisible()) {
                                MainActivity.this.mapFragment.locationMarkerList.add( MainActivity.this.mapFragment.mGoolgeMap.addMarker( markerOptions ) );
                                if (!MainActivity.this.mapFragment.locationEnableCheckbox.isChecked()) {
                                    for (Marker marker : MainActivity.this.mapFragment.locationMarkerList) {
                                        marker.setVisible( false );
                                    }
                                }
                            }
                        }
                    }
                }
                MainActivity.this.locationFragment.loadData( arrayOfPlaceData );
            }
        });
    }

    private void setFragment(Fragment fragment) {
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        fragmentTransaction.replace(R.id.fragment_container, fragment);
        fragmentTransaction.commit();
    }

    /**
     * Return the current state of the permissions needed.
     */
    private boolean checkPermissions() {
        int finePermissionState = ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        int coarsePermissionState = ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION);
        return finePermissionState == PackageManager.PERMISSION_GRANTED || coarsePermissionState == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        boolean shouldProvideRationale =
                ActivityCompat.shouldShowRequestPermissionRationale(this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION);

        // Provide an additional rationale to the user. This would happen if the user denied the
        // request previously, but didn't check the "Don't ask again" checkbox.
        // Provide an additional rationale to the user. This would happen if the user denied the
        // request previously, but didn't check the "Don't ask again" checkbox.
        if (shouldProvideRationale) {
            Log.i("WoosmapGeofencing", "Displaying permission rationale to provide additional context.");
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQUEST_PERMISSIONS_REQUEST_CODE);
        } else {
            Log.i("WoosmapGeofencing", "Requesting permission");
            // Request permission. It's possible this can be auto answered if device policy
            // sets the permission in a given state or the user denied the permission
            // previously and checked "Never ask again".
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQUEST_PERMISSIONS_REQUEST_CODE);
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult( requestCode, permissions, grantResults );
        Log.i( "WoosmapGeofencing", "onRequestPermissionResult" );
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            if (grantResults.length <= 0) {
                // If user interaction was interrupted, the permission request is cancelled and you
                // receive empty arrays.
                Log.i( "WoosmapGeofencing", "User interaction was cancelled." );
            } else if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i( "WoosmapGeofencing", "Permission granted, updates requested, starting location updates" );
            } else {
                // Permission denied.

                // Notify the user via a SnackBar that they have rejected a core permission for the
                // app, which makes the Activity useless. In a real app, core permissions would
                // typically be best requested during a welcome-screen flow.

                // Additionally, it is important to remember that a permission might have been
                // rejected without asking the user for permission (device policy or "Never ask
                // again" prompts). Therefore, a user interface affordance is typically implemented
                // when permissions are denied. Otherwise, your app could appear unresponsive to
                // touches or interactions which have required permissions.
                showSnackbar( R.string.permission_denied_explanation, R.string.settings,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                // Build intent that displays the App settings screen.
                                Intent intent = new Intent();
                                intent.setAction(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS );
                                Uri uri = Uri.fromParts( "package", getPackageName(), null );
                                intent.setData( uri );
                                intent.setFlags( Intent.FLAG_ACTIVITY_NEW_TASK );
                                startActivity( intent );
                            }
                        } );
            }
        }
    }

    /**
     * Shows a {@link Snackbar}.
     *
     * @param mainTextStringId The id for the string resource for the Snackbar text.
     * @param actionStringId   The text of the action item.
     * @param listener         The listener associated with the Snackbar action.
     */
    private void showSnackbar(final int mainTextStringId, final int actionStringId,
                              View.OnClickListener listener) {
        Snackbar.make(findViewById(android.R.id.content),
                getString(mainTextStringId),
                Snackbar.LENGTH_INDEFINITE)
                .setAction(getString(actionStringId), listener).show();
    }


    public static class clearDBTask extends AsyncTask<Void, Void, Void> {
        private final Context mContext;
        public MainActivity mActivity;

        clearDBTask(Context context, MainActivity activity) {
            mContext = context;
            mActivity = activity;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            WoosmapDb.getInstance(mContext).clearAllTables();
            Woosmap.getInstance().removeGeofence();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (mActivity.locationFragment.adapter != null)
                mActivity.locationFragment.clearData();
            mActivity.mapFragment.clearMarkers();
        }
    }
}

package io.ona.kujaku.views;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.location.Location;
import android.os.AsyncTask;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.cocoahero.android.geojson.Feature;
import com.cocoahero.android.geojson.Point;
import com.karumi.dexter.Dexter;
import com.karumi.dexter.listener.single.PermissionListener;
import com.mapbox.android.gestures.MoveGestureDetector;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.mapboxsdk.annotations.IconFactory;
import com.mapbox.mapboxsdk.annotations.MarkerOptions;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.MapboxMapOptions;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.mapboxsdk.style.layers.CircleLayer;
import com.mapbox.mapboxsdk.style.layers.PropertyFactory;
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource;
import com.mapbox.mapboxsdk.style.sources.Source;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.ona.kujaku.R;
import io.ona.kujaku.callables.AsyncTaskCallable;
import io.ona.kujaku.callbacks.AddPointCallback;
import io.ona.kujaku.interfaces.IKujakuMapView;
import io.ona.kujaku.interfaces.ILocationClient;
import io.ona.kujaku.listeners.BaseLocationListener;
import io.ona.kujaku.listeners.OnFinishedListener;
import io.ona.kujaku.listeners.OnLocationChanged;
import io.ona.kujaku.location.clients.AndroidLocationClient;
import io.ona.kujaku.location.clients.GPSLocationClient;
import io.ona.kujaku.tasks.GenericAsyncTask;
import io.ona.kujaku.utils.LocationPermissionListener;
import io.ona.kujaku.utils.LocationSettingsHelper;
import io.ona.kujaku.utils.LogUtil;
import io.ona.kujaku.utils.NetworkUtil;
import io.ona.kujaku.utils.Permissions;
import io.ona.kujaku.utils.Views;

import static com.mapbox.mapboxsdk.style.expressions.Expression.exponential;
import static com.mapbox.mapboxsdk.style.expressions.Expression.get;
import static com.mapbox.mapboxsdk.style.expressions.Expression.interpolate;
import static com.mapbox.mapboxsdk.style.expressions.Expression.match;
import static com.mapbox.mapboxsdk.style.expressions.Expression.rgb;
import static com.mapbox.mapboxsdk.style.expressions.Expression.stop;
import static com.mapbox.mapboxsdk.style.expressions.Expression.zoom;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.circleColor;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.circleRadius;

/**
 * Created by Ephraim Kigamba - ekigamba@ona.io on 26/09/2018
 */

public class KujakuMapView extends MapView implements IKujakuMapView {

    private static final String TAG = KujakuMapView.class.getName();
    public static final double LOCATION_FOCUS_ZOOM = 20d;

    private boolean canAddPoint = false;

    private ImageView markerLayout;
    private Button doneAddingPoint;
    private Button cancelAddingPoint;
    private ImageButton addPoint;
    private MapboxMap mapboxMap;
    private ImageButton currentLocationBtn;

    private LinearLayout buttonsLayout;

    private CircleLayer userLocationInnerCircle;
    private CircleLayer userLocationOuterCircle;
    private GeoJsonSource pointsSource;
    private String pointsInnerLayerId = UUID.randomUUID().toString();
    private String pointsOuterLayerId = pointsInnerLayerId + "2";
    private String pointsSourceId = UUID.randomUUID().toString();

    private ILocationClient locationClient;
    private Toast currentlyShownToast;
    private OnLocationChanged onLocationChangedListener;
    private boolean isMapScrolled = false;

    private static final int ANIMATE_TO_LOCATION_DURATION = 1000;

    private LatLng latestLocation;
    private boolean updateUserLocationOnMap = false;

    private JSONObject featureCollectionJSON;
    private GeoJsonSource geoJsonSource;

    private Map<String, Integer> featureMap;

    public KujakuMapView(@NonNull Context context) {
        super(context);
        init(null);
    }

    public KujakuMapView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public KujakuMapView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    public KujakuMapView(@NonNull Context context, @Nullable MapboxMapOptions options) {
        super(context, options);
        init(null);
    }

    private void init(@Nullable AttributeSet attributeSet) {
        checkPermissions();

        markerLayout = findViewById(R.id.iv_mapview_locationSelectionMarker);
        doneAddingPoint = findViewById(R.id.btn_mapview_locationSelectionBtn);
        cancelAddingPoint = findViewById(R.id.btn_mapview_locationSelectionCancelBtn);

        addPoint = findViewById(R.id.imgBtn_mapview_locationAdditionBtn);
        currentLocationBtn = findViewById(R.id.ib_mapview_focusOnMyLocationIcon);

        buttonsLayout = findViewById(R.id.ll_mapview_locationSelectionBtns);
        currentLocationBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                focusOnUserLocation(true);
            }
        });

        markerLayout.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                markerLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                int height = markerLayout.getMeasuredHeight();
                markerLayout.setY(markerLayout.getY() - (height/2));
            }
        });

        Map<String, Object> attributes = extractStyleValues(attributeSet);
        String key = getContext().getString(R.string.current_location_btn_visibility);
        if (attributes.containsKey(key)) {
            boolean isCurrentLocationBtnVisible = (boolean) attributes.get(key);
            showCurrentLocationBtn(isCurrentLocationBtnVisible);
        }

        // initialize feature collection
        try {
            JSONArray featuresArray = new JSONArray();
            this.featureCollectionJSON = new JSONObject();
            this.featureCollectionJSON.put("type", "FeatureCollection");
            this.featureCollectionJSON.put("features", featuresArray);
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        }
        featureMap = new HashMap<>();

        // TODO: remove this button after testing
        Button button = findViewById(R.id.btn_test_properties_change);
        button.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (featureCollectionJSON.getJSONArray("features").length() == 0) {
                        List<com.mapbox.geojson.Feature> features = createFeatures(20, 36.768831, -1.284956);
                        addFeaturePoints(FeatureCollection.fromFeatures(features));
                    } else {
                        List<com.mapbox.geojson.Feature> features = createFeatures(10, 36.768831, -1.284956);
                        updateFeaturePointProperties(FeatureCollection.fromFeatures(features));
                    }
                } catch (JSONException e) {
                    Log.e(TAG, e.getMessage());
                }
            }
        });
    }

    private void showUpdatedUserLocation() {
        updateUserLocationLayer(latestLocation);

        if (updateUserLocationOnMap || !isMapScrolled) {
            // Focus on the new location
            centerMap(latestLocation, ANIMATE_TO_LOCATION_DURATION, getZoomToUse(mapboxMap, LOCATION_FOCUS_ZOOM));
        }
    }

    private void warmUpLocationServices() {
        GenericAsyncTask genericAsyncTask = new GenericAsyncTask(new AsyncTaskCallable() {
            @Override
            public Object[] call() throws Exception {
                return new Object[]{ NetworkUtil.isInternetAvailable()};
            }
        });
        genericAsyncTask.setOnFinishedListener(new OnFinishedListener() {
            @Override
            public void onSuccess(Object[] objects) {
                if ((boolean) objects[0]) {
                    // Use the fused location API
                    locationClient = new AndroidLocationClient(getContext());
                } else {
                    // Use the GPS hardware
                    locationClient = new GPSLocationClient(getContext());
                    // Update the location every 5 seconds
                    locationClient.setUpdateIntervals(5000, 5000);
                }

                locationClient.requestLocationUpdates(new BaseLocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        latestLocation = new LatLng(location.getLatitude()
                                , location.getLongitude());

                        if (onLocationChangedListener != null) {
                            onLocationChangedListener.onLocationChanged(location);
                        }

                        if (updateUserLocationOnMap) {
                            showUpdatedUserLocation();
                        }
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                LogUtil.e(TAG, e);
            }
        });
        genericAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private Map<String, Object> extractStyleValues(@Nullable AttributeSet attrs) {
        Map<String, Object> attributes = new HashMap<>();
        if (attrs != null) {
            TypedArray typedArray = getContext().getTheme().obtainStyledAttributes(attrs, R.styleable.KujakuMapView, 0, 0);
            try {
                boolean isCurrentLocationBtnVisible = typedArray.getBoolean(R.styleable.KujakuMapView_current_location_btn_visibility, false);
                attributes.put(getContext().getString(R.string.current_location_btn_visibility), isCurrentLocationBtnVisible);
            } catch (Exception e) {
                Log.d(TAG, e.getMessage());
            } finally {
                typedArray.recycle();
            }
        }
        return attributes;
    }

    @Override
    public void addPoint(boolean useGPS, @NonNull final AddPointCallback addPointCallback) {
        addPoint(useGPS, addPointCallback, null);
    }

    @Override
    public void addPoint(boolean useGPS, @NonNull AddPointCallback addPointCallback, @Nullable MarkerOptions markerOptions) {
        addPoint.setVisibility(VISIBLE);
        addPoint.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                addPoint.setVisibility(GONE);
                buttonsLayout.setVisibility(VISIBLE);

                if (useGPS) {
                    enableAddPoint(true, null);
                    doneAddingPoint.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            JSONObject featureJSON = dropPoint(markerOptions);
                            addPointCallback.onPointAdd(featureJSON);

                            enableAddPoint(false, null);

                            buttonsLayout.setVisibility(GONE);
                            addPoint.setVisibility(VISIBLE);
                        }
                    });
                } else {
                    enableAddPoint(true);
                    doneAddingPoint.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            JSONObject featureJSON = dropPoint(markerOptions);
                            addPointCallback.onPointAdd(featureJSON);

                            enableAddPoint(false);

                            buttonsLayout.setVisibility(GONE);
                            addPoint.setVisibility(VISIBLE);
                        }
                    });
                }

                cancelAddingPoint.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (useGPS) {
                            enableAddPoint(false, null);
                        } else {
                            enableAddPoint(false);
                        }

                        buttonsLayout.setVisibility(GONE);
                        addPoint.setVisibility(VISIBLE);
                    }
                });
            }
        });
    }

    @Override
    public void addPoint(boolean useGPS, @NonNull AddPointCallback addPointCallback, @DrawableRes int markerResourceId) {
        addPoint(useGPS, addPointCallback,
                new MarkerOptions().setIcon(IconFactory.getInstance(getContext()).fromResource(markerResourceId))
        );
    }

    @Override
    public void enableAddPoint(boolean canAddPoint) {
        this.canAddPoint = canAddPoint;

        if (this.canAddPoint) {
            // Show the layer with the marker in the middle
            showMarkerLayout();
        } else {
            hideMarkerLayout();
        }
    }

    public void setViewVisibility(View view, boolean isVisible) {
        view.setVisibility(isVisible ? VISIBLE : GONE);
    }

    @Override
    public void enableAddPoint(boolean canAddPoint, @Nullable final OnLocationChanged onLocationChanged) {
        isMapScrolled = false;
        this.enableAddPoint(canAddPoint);

        if (canAddPoint) {
            this.onLocationChangedListener = onLocationChanged;

            // 1. Focus on the location for the first time is a must
            // 2. Any sub-sequent location updates are dependent on whether the user has touched the UI
            // 3. Show the circle icon on the currrent position -> This will happen whenever there are location updates
            updateUserLocationOnMap = true;
            if (latestLocation != null) {
                showUpdatedUserLocation();
            }
        } else {
            // This should just disable the layout and any ongoing operations for focus
            this.onLocationChangedListener = null;
        }
    }

    private void updateUserLocationLayer(@NonNull LatLng latLng) {
        com.mapbox.geojson.Feature feature =
                com.mapbox.geojson.Feature.fromGeometry(
                        com.mapbox.geojson.Point.fromLngLat(
                                latLng.getLongitude(), latLng.getLatitude()
                        )
                );

        if (userLocationOuterCircle == null || userLocationInnerCircle == null || pointsSource == null) {
            pointsSource = new GeoJsonSource(pointsSourceId);
            pointsSource.setGeoJson(feature);

            if (mapboxMap != null && mapboxMap.getSource(pointsSourceId) == null) {
                mapboxMap.addSource(pointsSource);

                userLocationInnerCircle = new CircleLayer(pointsInnerLayerId, pointsSourceId);
                userLocationInnerCircle.setProperties(
                        circleColor("#4387f4"),
                        circleRadius(5f),
                        PropertyFactory.circleStrokeWidth(1f),
                        PropertyFactory.circleStrokeColor("#dde2e4")
                );

                userLocationOuterCircle = new CircleLayer(pointsOuterLayerId, pointsSourceId);
                userLocationOuterCircle.setProperties(
                        circleColor("#81c2ee"),
                        circleRadius(25f),
                        PropertyFactory.circleStrokeWidth(1f),
                        PropertyFactory.circleStrokeColor("#74b7f6"),
                        PropertyFactory.circleOpacity(0.3f),
                        PropertyFactory.circleStrokeOpacity(0.6f)
                );

                mapboxMap.addLayer(userLocationOuterCircle);
                mapboxMap.addLayer(userLocationInnerCircle);
            }
            // TODO: What if the map already has a source layer with this source layer id
        } else {
            // Get the layer and update it
            if (mapboxMap != null) {
                Source source = mapboxMap.getSource(pointsSourceId);

                if (source instanceof GeoJsonSource) {
                    ((GeoJsonSource) source).setGeoJson(feature);
                }
            }
        }
    }

    @Override
    public @Nullable JSONObject dropPoint() {
        return dropPoint((MarkerOptions) null);
    }

    @Nullable
    @Override
    public JSONObject dropPoint(@DrawableRes int markerResourceId) {
        MarkerOptions markerOptions = new MarkerOptions()
                .setIcon(IconFactory.getInstance(getContext()).fromResource(markerResourceId));

        return dropPoint(markerOptions);
    }

    @Override
    public @Nullable JSONObject dropPoint(@Nullable LatLng latLng) {
        return dropPoint(
                new MarkerOptions()
                        .setPosition(latLng)
        );
    }

    @Nullable
    @Override
    public JSONObject dropPoint(@Nullable MarkerOptions markerOptions) {
        if (mapboxMap != null && canAddPoint) {
            if (markerOptions != null && markerOptions.getPosition() != null) {
                LatLng latLng = markerOptions.getPosition();
                Feature feature = new Feature();
                feature.setGeometry(new Point(latLng.getLatitude(), latLng.getLongitude()));

                try {
                    JSONObject jsonObject = feature.toJSON();

                    // Add a layer with the current point
                    centerMap(latLng, ANIMATE_TO_LOCATION_DURATION, getZoomToUse(mapboxMap, getZoomToUse(mapboxMap, LOCATION_FOCUS_ZOOM)));
                    dropPointOnMap(latLng, markerOptions);

                    enableAddPoint(false);

                    this.onLocationChangedListener = null;

                    if (locationClient != null) {
                        locationClient.stopLocationUpdates();
                    }

                    return jsonObject;
                } catch (JSONException e) {
                    LogUtil.e(TAG, Log.getStackTraceString(e));
                }
            } else {
                LatLng latLng = mapboxMap.getCameraPosition().target;

                Feature feature = new Feature();
                feature.setGeometry(new Point(latLng.getLatitude(), latLng.getLongitude()));

                try {
                    JSONObject jsonObject = feature.toJSON();

                    // Add a layer with the current point
                    dropPointOnMap(latLng, markerOptions);

                    return jsonObject;
                } catch (JSONException e) {
                    LogUtil.e(TAG, Log.getStackTraceString(e));
                }
            }
        }

        return null;
    }

    @Nullable
    @Override
    public JSONObject dropPoint(@Nullable LatLng latLng, @DrawableRes int markerResourceId) {
        MarkerOptions markerOptions = new MarkerOptions()
                .setPosition(latLng)
                .setIcon(
                        IconFactory.getInstance(getContext())
                                .fromResource(markerResourceId)
                );

        return dropPoint(markerOptions);
    }

    private void showMarkerLayout() {
        markerLayout.setVisibility(VISIBLE);
    }

    private void hideMarkerLayout() {
        markerLayout.setVisibility(GONE);
    }

    private void getMapboxMap() {
        if (mapboxMap == null) {
            getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(MapboxMap mapboxMap) {
                    KujakuMapView.this.mapboxMap = mapboxMap;
                    mapboxMap.getUiSettings().setCompassEnabled(false);
                    // This disables
                    addOnScrollListenerToMap(mapboxMap);
                    setGeoJSONSource("kujaku_primary_source");
                    addMapBoxLayer(); // TODO: remove this after testing
                }
            });
        }
    }

    private void addOnScrollListenerToMap(MapboxMap mapboxMap) {
        mapboxMap.addOnMoveListener(new MapboxMap.OnMoveListener() {
            @Override
            public void onMoveBegin(@NonNull MoveGestureDetector detector) {
                isMapScrolled = true;

                // We should assume the user no longer wants us to focus on their location
                focusOnUserLocation(false);
            }

            @Override
            public void onMove(@NonNull MoveGestureDetector detector) {
                // We are not going to do anything here
            }

            @Override
            public void onMoveEnd(@NonNull MoveGestureDetector detector) {
                // We are also not going to do anything here
            }
        });
    }

    private void dropPointOnMap(@NonNull LatLng latLng) {
        dropPointOnMap(latLng, null);
    }

    private void dropPointOnMap(@NonNull LatLng latLng, @Nullable MarkerOptions markerOptionsParam) {
        MarkerOptions markerOptions = markerOptionsParam;
        if (markerOptions == null) {
            markerOptions = new MarkerOptions()
                    .position(latLng);
        } else if (markerOptions.getPosition() == null) {
            markerOptions.setPosition(latLng);
        }

        mapboxMap.addMarker(markerOptions);
    }

    public boolean isCanAddPoint() {
        return canAddPoint;
    }

    private void showToast(String text, int length, boolean override) {
        if (override && currentlyShownToast != null) {
            // TODO: This needs to be fixed because the currently showing toast will not be cancelled if another non-overriding toast was called after it
            currentlyShownToast.cancel();
        }

        currentlyShownToast = Toast.makeText(getContext(), text, length);
        currentlyShownToast.show();
    }

    public void centerMap(@NonNull LatLng point, int animateToNewTargetDuration, double newZoom) {
        CameraPosition.Builder cameraPositionBuilder = new CameraPosition.Builder()
                .target(point);
        if (newZoom != -1d) {
            cameraPositionBuilder.zoom(newZoom);
        }

        CameraPosition cameraPosition = cameraPositionBuilder.build();

        if (mapboxMap != null) {
            mapboxMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), animateToNewTargetDuration);
        }
    }

    public void centerMap(@NonNull LatLng point, int animateToNewTargetDuration) {
        centerMap(point, animateToNewTargetDuration, -1d);
    }

    private double getZoomToUse(@NonNull MapboxMap mapboxMap, double zoomLevel) {
        return mapboxMap == null ? zoomLevel : mapboxMap.getCameraPosition().zoom > zoomLevel ? -1d : zoomLevel;
    }

    @Override
    public void onStop() {
        super.onStop();

        // Clean up location services
        if (locationClient != null && locationClient.isMonitoringLocation()) {
            locationClient.setListener(null);
            locationClient.stopLocationUpdates();
            locationClient = null;
        }
    }

    @Override
    public void showCurrentLocationBtn(boolean isVisible) {
        currentLocationBtn.setVisibility(isVisible ? VISIBLE : GONE);
    }

    @Override
    public void focusOnUserLocation(boolean focusOnMyLocation) {
        if (focusOnMyLocation) {
            isMapScrolled = false;
            changeTargetIcon(R.drawable.ic_cross_hair_blue);

            // Enable the listener & show the current user location
            updateUserLocationOnMap = true;
            if (latestLocation != null) {
                showUpdatedUserLocation();
            }

        } else {
            updateUserLocationOnMap = false;
            changeTargetIcon(R.drawable.ic_cross_hair);
        }
    }

    private void changeTargetIcon(int drawableIcon) {
        Views.changeDrawable(currentLocationBtn, drawableIcon);
    }

    private void checkPermissions() {
        if (getContext() instanceof Activity) {
            final Activity activity = (Activity) getContext();
            PermissionListener dialogPermissionListener = new LocationPermissionListener(activity);

            Dexter.withActivity(activity)
                    .withPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    .withListener(dialogPermissionListener)
                    .check();
        } else {
            Log.wtf(TAG, "KujakuMapView was not started in an activity!! This is very bad or it is being used in tests. We are going to ignore the permissions check! Good luck");
        }
    }

    @Override
    public void addFeaturePoints(FeatureCollection featureCollection) throws JSONException {
        JSONArray featuresArray = this.featureCollectionJSON.getJSONArray("features");
        for (com.mapbox.geojson.Feature feature : featureCollection.features()) {
            String featureId = feature.id();
            if (!featureMap.containsKey(featureId)) {
                featureMap.put(featureId, featuresArray.length());
                featuresArray.put(new JSONObject(feature.toJson()));
            }
        }
        ((GeoJsonSource) mapboxMap.getSource(geoJsonSource.getId())).setGeoJson(featureCollectionJSON.toString());
    }

    @Override
    public void updateFeaturePointProperties(FeatureCollection featureCollection) throws JSONException {
        List<com.mapbox.geojson.Feature> newFeatures = new ArrayList<>();
        JSONArray featuresArray = this.featureCollectionJSON.getJSONArray("features");
        for (com.mapbox.geojson.Feature feature : featureCollection.features()) {
            String featureId = feature.id();
            if (featureMap.containsKey(featureId)) {
                int featureIndex = featureMap.get(featureId);
                featuresArray.put(featureIndex, new JSONObject(feature.toJson()));
            } else {
                newFeatures.add(feature);
            }
        }
        FeatureCollection newFeatureCollection = FeatureCollection.fromFeatures(newFeatures);
        addFeaturePoints(newFeatureCollection);
        ((GeoJsonSource) mapboxMap.getSource(geoJsonSource.getId())).setGeoJson(featureCollectionJSON.toString());
    }

    public MapboxMap getMapBoxMap() {
        return this.mapboxMap;
    }

    // TODO: remove this use what will be in utils
    private void setGeoJSONSource(String sourceId) {
        if (this.mapboxMap != null) {
            geoJsonSource = new GeoJsonSource(sourceId, featureCollectionJSON.toString());
            mapboxMap.addSource(geoJsonSource);
        }
    }

    // TODO: remove this use what will be in utils
    public void addMapBoxLayer() {

        CircleLayer circleLayer = new CircleLayer("kujaku-primary-layer", geoJsonSource.getId());

        circleLayer.setSourceLayer("sf2010");
        circleLayer.withProperties(
                circleRadius(
                        interpolate(
                                exponential(1.75f),
                                zoom(),
                                stop(12, 2f),
                                stop(22, 180f)
                        )),
                circleColor(
                        match(get("ethnicity"), rgb(0, 0, 0),
                                stop("White", rgb(251, 176, 59)),
                                stop("Black", rgb(34, 59, 83)),
                                stop("Hispanic", rgb(229, 94, 94)),
                                stop("Asian", rgb(59, 178, 208)),
                                stop("Other", rgb(204, 204, 204)))));

        this.mapboxMap.addLayer(circleLayer);
    }

    // TODO: remove this use what will be in utils
    private enum FeatureGroup {White, Black, Hispanic, Asian, Other};
    private final static int FEATURE_GROUP_SIZE = FeatureGroup.values().length;
    public List<com.mapbox.geojson.Feature> createFeatures(int numFeatures, double longitude, double latitude) throws JSONException {

        final double LAMBDA = 0.0001;

        double longitudeOffset;
        double latitudeOffset;
        double newLongitude = longitude;
        double newLatitude = latitude;

        int featureNumber = 0;
        int prevFeatureNumber = -1;

        List<com.mapbox.geojson.Feature> features = new ArrayList<>();
        while (featureNumber < numFeatures) {
            if (prevFeatureNumber != featureNumber) {
                JSONObject feature = new JSONObject();
                feature.put("id", "feature_" + featureNumber);
                feature.put("type", "Feature");

                int featureIndex = (int) (Math.random() * FEATURE_GROUP_SIZE);
                String featureValue = FeatureGroup.values()[featureIndex].toString();
                JSONObject properties = new JSONObject();
                properties.put("ethnicity", featureValue);
                feature.put("properties", properties);

                JSONObject geometry = new JSONObject();
                geometry.put("type", "Point");
                JSONArray coordinates = new JSONArray();
                coordinates.put(newLongitude);
                coordinates.put(newLatitude);
                geometry.put("coordinates", coordinates);

                feature.put("geometry", geometry);

                features.add(com.mapbox.geojson.Feature.fromJson(feature.toString()));
            }
            // housekeeping
            longitudeOffset = Math.random();
            latitudeOffset = Math.random();
            if (longitudeOffset >= LAMBDA || latitudeOffset >= LAMBDA) {
                featureNumber++;
                newLongitude += longitudeOffset;
                newLatitude += latitudeOffset;
            }
        }
        return features;
    }

    @Override
    public void onPause() {
        if (locationClient != null) {
            locationClient.stopLocationUpdates();
            locationClient.close();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getMapboxMap();

        // This prevents an overlay issue the first time when requesting for permissions
        if (Permissions.check(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (getContext() instanceof Activity) {
                final Activity activity = (Activity) getContext();
                LocationSettingsHelper.checkLocationEnabled(activity);
            }
            warmUpLocationServices();
        }
    }
}

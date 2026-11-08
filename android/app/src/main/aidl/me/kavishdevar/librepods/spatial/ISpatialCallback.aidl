package me.kavishdevar.librepods.spatial;

/** Status pushed from the shell user service to the app (JSON, see SpatialStatus). */
oneway interface ISpatialCallback {
    void onStatus(String json);
}

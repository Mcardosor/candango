package io.github.mcardosor.candango

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import io.github.mcardosor.candango.data.LatLon
import io.github.mcardosor.candango.ui.AppRoot
import io.github.mcardosor.candango.ui.AppViewModel
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val locationListener = LocationListener { loc: Location -> vm.onLocation(LatLon(loc.latitude, loc.longitude)) }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startLocation()
    }

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        Notifier.createChannels(this)
        // fim da viagem/aviso com o app fechado: desliga o que ficou ligado só por causa dele
        vm.onBackgroundWorkEnded = { if (!started) stopLive() }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0B8A5F), secondary = Color(0xFF0B8A5F))) {
                AppRoot(vm)
            }
        }
        if (!hasLocationPermission()) {
            permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        vm.startPolling()
        startLocation()
    }

    override fun onStop() {
        super.onStop()
        started = false
        // com viagem ou aviso ligado, o TripService mantém o app vivo e o GPS continua
        if (!vm.backgroundWork) stopLive()
    }

    override fun onDestroy() {
        vm.onBackgroundWorkEnded = null
        super.onDestroy()
    }

    private fun stopLive() {
        vm.stopPolling()
        locationManager.removeUpdates(locationListener)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPermission()) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
        providers.mapNotNull { locationManager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.let { vm.onLocation(LatLon(it.latitude, it.longitude)) }
        // a cada 5 s / 10 m: dentro do ônibus o "desça na próxima" precisa de posição recente
        for (p in providers) locationManager.requestLocationUpdates(p, 5_000L, 10f, locationListener)
    }
}

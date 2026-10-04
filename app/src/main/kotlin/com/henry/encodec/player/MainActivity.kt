package com.henry.encodec.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cl.cuy.emergencyradio.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ChileRed = Color(0xFF8E2430)
private val ChileRedLight = Color(0xFFF5E8E6)
private val WarmBackground = Color(0xFFFAF5F3)
private const val ONDABAJA_REPOSITORY_URL = "https://github.com/HenryDelMal/OndaBaja"
private enum class RadioPage { STATIONS, SETTINGS, ADVANCED, ABOUT, TECHNOLOGIES, TECHNOLOGY, LICENSE }
private enum class InterfaceMode(val label: String) { LIGHT("Claro"), DARK("Oscuro"), AUTO("Automático") }

private data class TechnologyInfo(
    val title: String,
    val description: String,
    val projects: List<TechnologyProject>,
)

private data class TechnologyProject(
    val title: String,
    val url: String,
    val licenseTitle: String? = null,
    val licenseAsset: String? = null,
)

private val technologies = listOf(
    TechnologyInfo(
        title = "OndaBaja",
        description = "El código fuente de OndaBaja está publicado bajo la licencia MIT y disponible en el repositorio público del proyecto.",
        projects = listOf(
            TechnologyProject(
                title = "Repositorio de OndaBaja",
                url = ONDABAJA_REPOSITORY_URL,
                licenseTitle = "Ver licencia MIT completa",
                licenseAsset = "licenses/EmergencyRadioCL-MIT.txt",
            ),
        ),
    ),
    TechnologyInfo(
        title = "EnCodec",
        description = "Meta EnCodec es el códec de audio cuyos tokens reciben las emisoras. Vocos reconstruye audio a partir de esa representación. La aplicación reproduce el perfil mono de 24 kHz, diseñado para mantener flujos de voz con tasas de bits muy bajas.",
        projects = listOf(TechnologyProject(
            title = "Proyecto EnCodec de Meta",
            url = "https://github.com/facebookresearch/encodec",
            licenseTitle = "Ver licencia MIT completa",
            licenseAsset = "licenses/EnCodec-Meta-MIT.txt",
        )),
    ),
    TechnologyInfo(
        title = "Vocos",
        description = "Vocos se basa en la representación de audio de EnCodec y reconstruye el sonido a partir de sus tokens. Esta aplicación utiliza Vocos con transmisiones mono de 24 kHz. Se reconoce el proyecto original y los componentes de Charactr incluidos bajo licencia MIT.",
        projects = listOf(
            TechnologyProject("Proyecto Vocos", "https://github.com/gemelo-ai/vocos", "Ver licencia MIT completa", "licenses/Vocos-MIT.txt"),
            TechnologyProject("Componentes Vocos EnCodec 24 kHz", "https://huggingface.co/charactr/vocos-encodec-24khz", "Ver licencia MIT completa", "licenses/Vocos-MIT.txt"),
        ),
    ),
    TechnologyInfo(
        title = "Vocos.cpp",
        description = "Vocos.cpp es la implementación nativa de Vocos mantenida por HenryDelMal. Está inspirada en la organización de encodec.cpp y adapta ese enfoque al procesamiento de audio Vocos basado en tokens EnCodec.",
        projects = listOf(
            TechnologyProject("Proyecto Vocos.cpp de HenryDelMal", "https://github.com/HenryDelMal/vocos.cpp", "Ver licencia MIT completa", "licenses/EmergencyRadioCL-MIT.txt"),
            TechnologyProject("Proyecto encodec.cpp de HenryDelMal (referencia)", "https://github.com/HenryDelMal/encodec.cpp", "Ver licencia MIT completa", "licenses/encodec-MIT.txt"),
            TechnologyProject("Proyecto encodec.cpp original de pfeatherstone (referencia)", "https://github.com/pfeatherstone/encodec.cpp", "Ver licencia MIT completa", "licenses/encodec-MIT.txt"),
        ),
    ),
    TechnologyInfo(
        title = "Eigen",
        description = "Eigen proporciona componentes matemáticos y FFT portables utilizados por el código nativo del decodificador. Su implementación FFT derivada de KissFFT conserva la atribución a Mark Borgerding y el aviso de licencia MPL 2.0.",
        projects = listOf(TechnologyProject(
            title = "Proyecto Eigen",
            url = "https://eigen.tuxfamily.org/",
            licenseTitle = "Ver licencia MPL 2.0 completa",
            licenseAsset = "licenses/Eigen-MPL2.txt",
        )),
    ),
    TechnologyInfo(
        title = "KissFFT",
        description = "Eigen incluye una implementación FFT derivada de KissFFT. El código FFT que se compila en esta aplicación es el archivo incluido en Eigen, que conserva su aviso MPL 2.0 y la atribución a Mark Borgerding. Esta página enlaza también la licencia BSD-3-Clause del proyecto KissFFT original.",
        projects = listOf(TechnologyProject(
            title = "Proyecto KissFFT de Mark Borgerding",
            url = "https://github.com/mborgerding/kissfft",
            licenseTitle = "Ver licencia BSD 3-Clause completa",
            licenseAsset = "licenses/BSD-3-Clause.txt",
        )),
    ),
    TechnologyInfo(
        title = "AndroidX y Jetpack Compose",
        description = "La interfaz de Android y sus componentes visuales usan bibliotecas AndroidX y Jetpack Compose.",
        projects = listOf(
            TechnologyProject("Proyecto AndroidX", "https://github.com/androidx/androidx", "Ver licencia Apache 2.0 completa", "licenses/Apache-2.0.txt"),
            TechnologyProject("Código fuente de Jetpack Compose", "https://github.com/androidx/androidx/tree/androidx-main/compose", "Ver licencia Apache 2.0 completa", "licenses/Apache-2.0.txt"),
        ),
    ),
    TechnologyInfo(
        title = "Kotlin",
        description = "La aplicación está escrita en Kotlin y usa Kotlin Coroutines para coordinar las tareas de red y reproducción.",
        projects = listOf(
            TechnologyProject("Proyecto Kotlin", "https://github.com/JetBrains/kotlin", "Ver licencia Apache 2.0 completa", "licenses/Apache-2.0.txt"),
            TechnologyProject("Proyecto kotlinx.coroutines", "https://github.com/Kotlin/kotlinx.coroutines", "Ver licencia Apache 2.0 completa", "licenses/Apache-2.0.txt"),
        ),
    ),
    TechnologyInfo(
        title = "Cronet y Google Play services",
        description = "Cronet permite negociar HTTP/3 sobre QUIC y HTTP/2 mediante Google Play services. Si el proveedor no está disponible, la aplicación vuelve a la conexión HTTPS estándar de Android. El motor nativo se entrega desde Google Play services y no se incluye en el APK.",
        projects = listOf(
            TechnologyProject("Documentación de Cronet para Android", "https://developer.android.com/develop/connectivity/cronet", "Ver avisos y licencias completas", "licenses/GooglePlayServices-Cronet-ThirdPartyNotices.txt"),
            TechnologyProject("Avisos de código abierto de Google Play services", "https://developers.google.com/android/guides/opensource"),
        ),
    ),
    TechnologyInfo(
        title = "Brotli",
        description = "OndaBaja acepta manifiestos comprimidos con Brotli para reducir los bytes transferidos. La biblioteca decodificadora de Java permite recuperar el JSON original en el dispositivo.",
        projects = listOf(TechnologyProject(
            "Proyecto Brotli de Google",
            "https://github.com/google/brotli",
            "Ver licencia MIT completa",
            "licenses/Brotli-MIT.txt",
        )),
    ),
)

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN = "cl.cuy.emergencyradio.OPEN"
        const val ACTION_PLAY_PAUSE = "cl.cuy.emergencyradio.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "cl.cuy.emergencyradio.PREVIOUS"
        const val ACTION_NEXT = "cl.cuy.emergencyradio.NEXT"
        const val ACTION_STOP = "cl.cuy.emergencyradio.STOP"
        const val ACTION_JUMP_LIVE = "cl.cuy.emergencyradio.JUMP_TO_LIVE"
    }

    private val playerModel: PlayerViewModel by lazy { (application as PlayerApplication).playerModel }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("emergency_radio_settings", MODE_PRIVATE)
        val mode = runCatching { InterfaceMode.valueOf(preferences.getString("interface_mode", "AUTO")!!) }
            .getOrDefault(InterfaceMode.AUTO)
        setContent {
            var selectedMode by remember { mutableStateOf(mode) }
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = when (selectedMode) {
                InterfaceMode.LIGHT -> false
                InterfaceMode.DARK -> true
                InterfaceMode.AUTO -> systemDark
            }
            val scheme = if (dark) darkColorScheme(
                primary = Color(0xFFE58A91), onPrimary = Color(0xFF4B0710),
                primaryContainer = Color(0xFF6A1721), onPrimaryContainer = Color(0xFFFFDADB),
                background = Color(0xFF171213), surface = Color(0xFF211A1B),
                onSurface = Color(0xFFF3E7E6), secondary = Color(0xFFB6CDBE),
            ) else lightColorScheme(
                primary = ChileRed, onPrimary = Color.White,
                primaryContainer = ChileRedLight, onPrimaryContainer = ChileRed,
                background = WarmBackground, surface = Color.White,
                secondary = Color(0xFF49645A),
            )
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = scheme.background) {
                    EmergencyRadioScreen(
                        playerModel,
                        selectedMode,
                        onModeChange = {
                            selectedMode = it
                            preferences.edit().putString("interface_mode", it.name).apply()
                        },
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}

@Composable
private fun EmergencyRadioScreen(model: PlayerViewModel, mode: InterfaceMode, onModeChange: (InterfaceMode) -> Unit) {
    val state by model.state.collectAsState()
    var page by remember { mutableStateOf(RadioPage.STATIONS) }
    var menuExpanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selectedStationTab by remember { mutableIntStateOf(0) }
    var selectedTechnology by remember { mutableStateOf(technologies.first()) }
    var selectedLicense by remember { mutableStateOf(TechnologyProject("", "", "", "")) }
    val context = LocalContext.current
    val settings = remember(context) { context.getSharedPreferences("emergency_radio_settings", Context.MODE_PRIVATE) }
    var networkProtocol by remember { mutableStateOf(NetworkProtocolSettings.current()) }
    var compressionPreference by remember { mutableStateOf(HttpCompressionSettings.current()) }
    var tcpEnabled by remember { mutableStateOf(TcpTransportSettings.current()) }
    var starredIds by remember {
        mutableStateOf(settings.getStringSet("starred_station_ids", emptySet()).orEmpty().toSet())
    }
    val dark = androidx.compose.foundation.isSystemInDarkTheme() && mode == InterfaceMode.AUTO || mode == InterfaceMode.DARK
    val primaryText = if (dark) Color(0xFFF3E7E6) else Color(0xFF302426)
    val secondaryText = if (dark) Color(0xFFC0B2B1) else Color(0xFF786A69)
    LaunchedEffect(Unit) { model.loadStationCatalog() }
    fun goBack() {
        page = when (page) {
            RadioPage.LICENSE -> RadioPage.TECHNOLOGY
            RadioPage.TECHNOLOGY -> RadioPage.TECHNOLOGIES
            RadioPage.TECHNOLOGIES -> RadioPage.ABOUT
            RadioPage.ADVANCED -> RadioPage.SETTINGS
            else -> RadioPage.STATIONS
        }
    }
    BackHandler(page != RadioPage.STATIONS) { goBack() }

    if (state.preparingModel) {
        AlertDialog(onDismissRequest = {}, title = { Text("Preparando la aplicación") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = ChileRed)
                Text("Preparando el modelo de audio. Esto puede tardar un momento la primera vez o después de una actualización.")
            } }, confirmButton = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false))
    }
    if (!state.modelReady && !state.preparingModel && state.error != null) {
        AlertDialog(onDismissRequest = {}, title = { Text("No se pudo preparar la aplicación") }, text = { Text(state.error.orEmpty()) },
            confirmButton = { TextButton(onClick = model::prepareModel) { Text("Reintentar") } })
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(WarmBackground, CircleShape), contentAlignment = Alignment.Center) {
                Image(
                    painterResource(R.drawable.ic_ondabaja_logo),
                    contentDescription = "OndaBaja",
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val pageTitle = when (page) {
                    RadioPage.STATIONS -> "OndaBaja"
                    RadioPage.SETTINGS, RadioPage.ADVANCED -> "Ajustes"
                    RadioPage.ABOUT -> "Acerca de"
                    RadioPage.TECHNOLOGIES -> "Tecnologías y atribuciones"
                    RadioPage.TECHNOLOGY -> selectedTechnology.title
                    RadioPage.LICENSE -> "Licencia completa"
                }
                Text(pageTitle,
                    fontWeight = FontWeight.Bold, fontSize = 19.sp, color = primaryText)
                if (page == RadioPage.STATIONS) Text("Información cuando más importa", style = MaterialTheme.typography.labelMedium, color = secondaryText)
            }
            if (page != RadioPage.STATIONS) TextButton(onClick = ::goBack) { Text("Volver") }
            else Box {
                IconButton(onClick = { menuExpanded = true }) { Text("⋮", fontSize = 26.sp, color = primaryText) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Ajustes") }, onClick = { menuExpanded = false; page = RadioPage.SETTINGS })
                    DropdownMenuItem(text = { Text("Acerca de") }, onClick = { menuExpanded = false; page = RadioPage.ABOUT })
                }
            }
        }

        when (page) {
            RadioPage.STATIONS -> {
                state.error?.let { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE9E5)), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Text(message, Modifier.padding(14.dp), color = Color(0xFF7A1F25), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    singleLine = true, shape = RoundedCornerShape(16.dp), label = { Text("Buscar emisoras") },
                    trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("Borrar") } })
                TabRow(selectedTabIndex = selectedStationTab, modifier = Modifier.padding(bottom = 10.dp)) {
                    Tab(selected = selectedStationTab == 0, onClick = { selectedStationTab = 0 }, text = { Text("Todas") })
                    Tab(selected = selectedStationTab == 1, onClick = { selectedStationTab = 1 }, text = { Text("Favoritas") })
                }
                Text("EMISORAS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                val navigationStations = if (selectedStationTab == 1) state.catalog.filter { it.id in starredIds } else state.catalog
                SideEffect { model.setStationNavigationList(navigationStations.map { it.id }) }
                val normalizedQuery = normalizeStationSearch(search)
                val filtered = navigationStations.filter { station ->
                    normalizedQuery.isBlank() || listOfNotNull(station.name, station.region, station.description)
                        .any { normalizeStationSearch(it).contains(normalizedQuery) }
                }
                if (state.catalog.isEmpty() && state.loadingCatalog) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ChileRed) }
                } else if (filtered.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when {
                                state.catalog.isEmpty() -> "No hay emisoras disponibles"
                                selectedStationTab == 1 && search.isBlank() -> "Aún no tienes emisoras favoritas"
                                else -> "No se encontraron emisoras"
                            }, fontWeight = FontWeight.SemiBold, color = primaryText,
                        )
                        Text(if (state.catalog.isEmpty()) "Comprueba tu conexión e inténtalo de nuevo."
                            else if (selectedStationTab == 1 && search.isBlank()) "Marca una estrella en cualquier emisora para guardarla aquí."
                            else "Prueba con otro nombre o región.",
                            Modifier.padding(top = 6.dp), color = secondaryText)
                        if (state.catalog.isEmpty()) Button(onClick = model::loadStationCatalog, Modifier.padding(top = 14.dp)) { Text("Reintentar") }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                        items(filtered, key = { it.id }) { station ->
                            val selected = station.id == state.currentStationId
                            Card(modifier = Modifier.fillMaxWidth().clickable(enabled = state.modelReady) { model.playStation(station) },
                                shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(42.dp).background(if (selected) ChileRed else if (dark) Color(0xFF382D2E) else Color(0xFFF0ECEA), CircleShape), contentAlignment = Alignment.Center) {
                                        LiveSignalIcon(
                                            color = if (selected) Color.White else MaterialTheme.colorScheme.primary,
                                            animate = selected && state.playing,
                                            modifier = Modifier.size(26.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(13.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(station.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = primaryText)
                                        station.region?.let { Text(it, color = secondaryText, style = MaterialTheme.typography.bodySmall) }
                                        station.description?.takeIf(String::isNotBlank)?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall,
                                                color = secondaryText, modifier = Modifier.padding(top = 3.dp))
                                        }
                                    }
                                    if (selected && state.playing) Text("EN VIVO", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    IconButton(onClick = {
                                        val updated = starredIds.toMutableSet().apply {
                                            if (!add(station.id)) remove(station.id)
                                        }.toSet()
                                        starredIds = updated
                                        settings.edit().putStringSet("starred_station_ids", updated).apply()
                                    }) {
                                        Text(if (station.id in starredIds) "★" else "☆",
                                            color = if (station.id in starredIds) MaterialTheme.colorScheme.primary else secondaryText,
                                            fontSize = 24.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                NowPlayingCard(state, model)
            }
            RadioPage.SETTINGS -> SettingsPage(Modifier.weight(1f), mode, onModeChange, onAdvanced = { page = RadioPage.ADVANCED })
            RadioPage.ADVANCED -> AdvancedSettingsPage(
                Modifier.weight(1f), state.diagnosticsEnabled, model::toggleDiagnostics,
                networkProtocol = networkProtocol,
                compressionPreference = compressionPreference,
                tcpEnabled = tcpEnabled,
                onNetworkProtocolChange = {
                    networkProtocol = it
                    NetworkProtocolSettings.set(context, it)
                },
                onCompressionPreferenceChange = {
                    compressionPreference = it
                    HttpCompressionSettings.set(context, it)
                },
                onTcpEnabledChange = {
                    tcpEnabled = it
                    TcpTransportSettings.set(context, it)
                },
            )
            RadioPage.ABOUT -> AboutPage(Modifier.weight(1f),
                onTechnologies = { page = RadioPage.TECHNOLOGIES })
            RadioPage.TECHNOLOGIES -> TechnologiesPage(Modifier.weight(1f)) { technology ->
                selectedTechnology = technology
                page = RadioPage.TECHNOLOGY
            }
            RadioPage.TECHNOLOGY -> TechnologyPage(Modifier.weight(1f), selectedTechnology,
                onOpenLink = { openExternalLink(context, it) }, onLicense = {
                    selectedLicense = it
                    page = RadioPage.LICENSE
                })
            RadioPage.LICENSE -> LicensePage(Modifier.weight(1f), selectedLicense)
        }
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, mode: InterfaceMode, onModeChange: (InterfaceMode) -> Unit, onAdvanced: () -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSection("Interfaz") {
            Text("Modo de apariencia", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InterfaceMode.entries.forEach { option ->
                    FilterChip(selected = option == mode, onClick = { onModeChange(option) }, label = { Text(option.label) })
                }
            }
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = onAdvanced), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Avanzado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Opciones para diagnóstico y desarrollo", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AdvancedSettingsPage(
    modifier: Modifier,
    debugLogs: Boolean,
    onDebugChange: () -> Unit,
    networkProtocol: NetworkProtocolMode,
    compressionPreference: HttpCompressionPreference,
    tcpEnabled: Boolean,
    onNetworkProtocolChange: (NetworkProtocolMode) -> Unit,
    onCompressionPreferenceChange: (HttpCompressionPreference) -> Unit,
    onTcpEnabledChange: (Boolean) -> Unit,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSection("Transporte TCP personalizado") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Usar TCP cuando la emisora lo admita", fontWeight = FontWeight.Medium)
                    Text("Está activado de forma predeterminada cuando la emisora anuncia tcp=true y tcp_url. Si TCP no conecta al iniciar, usa HTTP/HTTPS. Si se interrumpe después, intenta reconectar por TCP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = tcpEnabled, onCheckedChange = onTcpEnabledChange)
            }
            Text("ELTCP no cifra ni comprime los datos. Desactívalo si prefieres HTTP/HTTPS. El cambio se aplica al iniciar la reproducción.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSection("Protocolo de red") {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
            ) {
                OutlinedTextField(
                    value = networkProtocol.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Protocolo") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    NetworkProtocolMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onNetworkProtocolChange(option)
                                expanded = false
                            },
                        )
                    }
                }
            }
            Text(
                when (networkProtocol) {
                    NetworkProtocolMode.DEFAULT -> "Usa HTTP/3 cuando está disponible, con fallback a HTTP/2 y HTTPS."
                    NetworkProtocolMode.HTTP3 -> "Prefiere HTTP/3 y continúa con HTTP/2 o HTTPS si hace falta."
                    NetworkProtocolMode.HTTP2 -> "Usa HTTP/2 y continúa con HTTPS estándar si hace falta."
                    NetworkProtocolMode.HTTPS -> "Usa HTTPS estándar de Android."
                    NetworkProtocolMode.HTTP -> "Usa HTTP sin cifrado. Las solicitudes y el audio pueden ser observados o modificados en tránsito."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSection("Compresión HTTP preferida") {
            var compressionExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = compressionExpanded,
                onExpandedChange = { compressionExpanded = !compressionExpanded },
            ) {
                OutlinedTextField(
                    value = compressionPreference.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Compresión") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = compressionExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = compressionExpanded,
                    onDismissRequest = { compressionExpanded = false },
                ) {
                    HttpCompressionPreference.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onCompressionPreferenceChange(option)
                                compressionExpanded = false
                            },
                        )
                    }
                }
            }
            Text(
                when (compressionPreference) {
                    HttpCompressionPreference.DEFAULT -> "Prefiere Brotli, luego GZip y finalmente respuestas sin comprimir."
                    HttpCompressionPreference.BROTLI -> "Solicita Brotli cuando el servidor lo admite."
                    HttpCompressionPreference.GZIP -> "Solicita GZip cuando el servidor lo admite."
                    HttpCompressionPreference.UNCOMPRESSED -> "Solicita respuestas sin comprimir."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSection("Registros de depuración") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Registros para desarrolladores", fontWeight = FontWeight.Medium)
                    Text("Registra métricas de red en el dispositivo para consultarlas después, incluso si se pierde la conexión.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = debugLogs, onCheckedChange = { onDebugChange() })
            }
            Text("Se guardan los tiempos de respuesta y descarga, bytes de contenido, tráfico total aproximado de la aplicación, reintentos, errores y cambios de red (tipo y velocidad estimada). Los registros quedan en el teléfono aunque no haya conexión. Para capturarlos en una APK de lanzamiento: adb logcat -v threadtime -s EnCodecLive:I EnCodecDecoder:I. Los registros privados permanecen en el teléfono, pero run-as no está disponible en una APK de lanzamiento.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("El tráfico total es una estimación a nivel de la aplicación e incluye otros intercambios de red que coincidan durante cada medición. Android no expone por separado el coste de DNS, TCP, TLS y cabeceras HTTP para cada solicitud.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun AboutPage(modifier: Modifier, onTechnologies: () -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AboutSection("Acerca de esta aplicación") {
            Text("OndaBaja facilita el acceso a emisoras informativas en lugares donde la conexión de datos es lenta o está congestionada. La aplicación recibe la transmisión en segmentos breves y reconstruye el audio directamente en el teléfono.")
            Text("El audio utiliza EnCodec en tasas de bits muy bajas, desde aproximadamente 1,5 kbps. Este enfoque está optimizado principalmente para la voz y las noticias. La música puede escucharse con menos fidelidad y algunos detalles sonoros pueden perderse.")
            Text("Esta aplicación necesita una conexión a Internet activa. Si la conexión es lenta, puede tardar en cargar o reproducir las emisoras. Sin conexión, no funcionará.", fontWeight = FontWeight.Bold)
            Text("Su propósito es ayudar a mantenerse informado cuando el acceso a Internet funciona con dificultad.")
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = onTechnologies), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tecnologías y atribuciones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Proyectos utilizados, enlaces y licencias", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun TechnologiesPage(modifier: Modifier, onSelect: (TechnologyInfo) -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        technologies.forEach { technology ->
            Card(Modifier.fillMaxWidth().clickable { onSelect(technology) }, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(technology.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                        Text("Enlaces a los proyectos y textos completos de sus licencias", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun TechnologyPage(
    modifier: Modifier,
    technology: TechnologyInfo,
    onOpenLink: (String) -> Unit,
    onLicense: (TechnologyProject) -> Unit,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AboutSection("Atribución") { Text(technology.description) }
        if (technology.title == "OndaBaja") {
            AboutSection("Código fuente") {
                Text("El código fuente completo está disponible públicamente en el repositorio de OndaBaja.")
                OutlinedButton(onClick = { onOpenLink(ONDABAJA_REPOSITORY_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ver repositorio")
                }
            }
        }
        technology.projects.forEach { project ->
            AboutSection(project.title) {
                OutlinedButton(onClick = { onOpenLink(project.url) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir proyecto", modifier = Modifier.weight(1f))
                    Text("↗")
                }
                if (project.licenseAsset != null && project.licenseTitle != null) {
                    Button(onClick = { onLicense(project) }, modifier = Modifier.fillMaxWidth()) {
                        Text(project.licenseTitle)
                    }
                }
            }
        }
    }
}

@Composable
private fun LicensePage(modifier: Modifier, project: TechnologyProject) {
    val context = LocalContext.current
    val licenseText = remember(project.licenseAsset) {
        runCatching {
            context.assets.open(requireNotNull(project.licenseAsset)).bufferedReader().use { it.readText() }
        }.getOrElse { "No se pudo cargar el texto de esta licencia." }
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 18.dp)) {
        SelectionContainer {
            Text(licenseText, style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun openExternalLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun LiveSignalIcon(color: Color, animate: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "live-signal")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Restart),
        label = "live-signal-pulse",
    )
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val unit = size.minDimension / 26f
        val stroke = 2f * unit
        fun wave(left: Boolean, radius: Float): Path {
            val direction = if (left) -1f else 1f
            return Path().apply {
                moveTo(cx + direction * radius * 0.45f, cy - radius)
                cubicTo(cx + direction * radius * 1.2f, cy - radius * 0.45f,
                    cx + direction * radius * 1.2f, cy + radius * 0.45f,
                    cx + direction * radius * 0.45f, cy + radius)
            }
        }
        if (animate) {
            listOf(0f, 0.5f).forEach { offset ->
                val phase = (pulse + offset) % 1f
                val radius = (3f + phase * 8f) * unit
                val waveColor = color.copy(alpha = (1f - phase) * 0.9f)
                drawPath(wave(true, radius), waveColor, style = Stroke(stroke))
                drawPath(wave(false, radius), waveColor, style = Stroke(stroke))
            }
        } else {
            listOf(6f, 10f).forEach { radius ->
                drawPath(wave(true, radius * unit), color, style = Stroke(stroke))
                drawPath(wave(false, radius * unit), color, style = Stroke(stroke))
            }
        }
        drawCircle(color, radius = 2.2f * unit, center = androidx.compose.ui.geometry.Offset(cx, cy))
    }
}

@Composable
private fun NowPlayingCard(state: PlayerState, model: PlayerViewModel) {
    val station = state.catalog.firstOrNull { it.id == state.currentStationId }
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = ChileRed)) {
        Row(Modifier.fillMaxWidth().padding(start = 17.dp, end = 10.dp, top = 13.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (state.playing) "AHORA EN VIVO" else "RADIO", color = Color(0xFFFFDAD4), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(station?.name ?: "Elige una emisora", Modifier.padding(top = 3.dp), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(station?.region ?: "Radios de emergencia de Chile", color = Color(0xFFF4D8D5), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = model::previousStation, enabled = station != null && state.modelReady) { Text("◀", color = Color.White) }
            IconButton(
                onClick = if (state.playing) model::stop else model::playPause,
                enabled = station != null && state.modelReady,
                modifier = Modifier.size(48.dp).background(Color.White, CircleShape),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.playing && state.live?.buffering == true) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp).semantics {
                                contentDescription = "Cargando audio"
                            },
                            color = ChileRed,
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (state.playing) "■" else "▶", color = ChileRed, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                }
            }
            IconButton(onClick = model::nextStation, enabled = station != null && state.modelReady) { Text("▶", color = Color.White) }
        }
    }
}

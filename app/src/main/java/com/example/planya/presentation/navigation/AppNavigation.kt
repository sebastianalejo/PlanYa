package com.example.planya.presentation.navigation

import android.net.Uri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import net.openid.appauth.AuthorizationService
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.planya.data.repository.PlanActionsRepository
import com.example.planya.data.photos.PlanPhotoRepository
import com.example.planya.data.auth.AuthSessionRequiredException
import com.example.planya.di.NetworkModule
import com.example.planya.di.RepositoryModule
import com.example.planya.domain.model.Plan
import com.example.planya.domain.model.PlanFeedScope
import com.example.planya.presentation.components.*
import com.example.planya.presentation.screens.access.*
import com.example.planya.presentation.screens.createplan.*
import com.example.planya.presentation.screens.home.*
import com.example.planya.presentation.screens.interests.InterestsScreen
import com.example.planya.presentation.screens.plandetail.PlanDetailScreen
import com.example.planya.presentation.screens.splash.SplashScreen
import com.example.planya.presentation.screens.profile.ProfileScreen
import com.example.planya.presentation.screens.profile.*
import com.example.planya.data.profile.ProfileRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AppNavigation(viewModel: HomeViewModel, account: AccountViewModel) {
    val context = LocalContext.current
    val oauthService = remember(context) { AuthorizationService(context) }
    DisposableEffect(oauthService) { onDispose { oauthService.dispose() } }
    val googleLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        account.googleResult(it.data)
    }
    fun signInGoogle() {
        val request = account.beginGoogle() ?: return
        try { googleLogin.launch(oauthService.getAuthorizationRequestIntent(request)) }
        catch (_: Exception) { account.googleLaunchFailed() }
    }
    val controller = rememberNavController()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val auth by account.state.collectAsStateWithLifecycle()
    val actions = remember(account) { PlanActionsRepository(NetworkModule.planActions, account.repository) }
    val photos = remember(account) { PlanPhotoRepository(context.applicationContext, NetworkModule.planPhotos, account.repository) }
    fun profileFactory(userId: String) = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(ProfileViewModel::class.java))
            val profiles = ProfileRepository(NetworkModule.profileApi, account.repository, photos, userId)
            @Suppress("UNCHECKED_CAST") return ProfileViewModel(profiles, photos.store, userId, extras.createSavedStateHandle()) as T
        }
    }
    var showLocation by remember { mutableStateOf(false) }
    var requestedFeed by rememberSaveable { mutableStateOf<PlanFeedScope?>(null) }
    var logoutRequested by rememberSaveable { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var pendingRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refreshOnReturn by rememberUpdatedState(newValue = {
        // No recargar un editor ni reemplazar su borrador cuando vuelve la cámara.
        when (controller.currentDestination?.route) {
            "inicio" -> viewModel.refresh()
            "plan/{planId}" -> {
                viewModel.refresh()
                controller.currentBackStackEntry?.arguments?.getString("planId")?.let(viewModel::openPlan)
            }
        }
        now = System.currentTimeMillis() / 1000
    })
    DisposableEffect(lifecycle) {
        val observer = ForegroundRefreshObserver { refreshOnReturn() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(15_000) }
    } }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.locationSaveError) {
        state.locationSaveError?.let { snackbars.showSnackbar(it); viewModel.dismissLocationError() }
    }
    fun message(text: String) { scope.launch { snackbars.showSnackbar(text) } }
    fun goAfterAccess() {
        val target = pendingRoute ?: "inicio"
        pendingRoute = null
        if (!controller.popBackStack(target, false)) controller.navigate(target) {
            val route = controller.currentDestination?.route
            if (route == "acceso") popUpTo("acceso") { inclusive = true }
            if (route == "intereses") popUpTo("intereses") { inclusive = true }
            launchSingleTop = true
        }
    }
    fun access(target: String) {
        pendingRoute = target
        account.mode(AccessMode.LOGIN)
        controller.navigate("acceso") { launchSingleTop = true }
    }
    fun navigate(tab: BottomTab) {
        when (tab) {
            BottomTab.HOME -> if (!controller.popBackStack("inicio", false)) controller.navigate("inicio") { launchSingleTop = true }
            BottomTab.PERFIL -> controller.navigate("perfil") { launchSingleTop = true }
            BottomTab.MAPA -> message("El mapa todavía no está disponible.")
        }
    }
    fun create() { controller.navigate("crear") { launchSingleTop = true } }
    LaunchedEffect(logoutRequested, auth.busy, auth.session) {
        if (logoutRequested && !auth.busy) {
            logoutRequested = false
            if (auth.session == null) {
                pendingRoute = null; requestedFeed = null
                controller.navigate("acceso") { popUpTo(controller.graph.id); launchSingleTop = true }
            }
        }
    }
    fun perform(plan: Plan, operation: String) {
        if (actionBusy) return
        if (auth.session == null) { access("plan/${Uri.encode(plan.id)}"); return }
        actionBusy = true
        scope.launch {
            try {
                val updated = when (operation) { "join" -> actions.join(plan.id); "leave" -> actions.leave(plan.id); else -> actions.cancel(plan.id) }
                if (operation == "cancel") {
                    viewModel.removePlan(plan.id)
                    controller.popBackStack("inicio", false)
                    message("Plan cancelado.")
                } else { viewModel.applyPlan(updated); message(if (operation == "join") "Ya estás en el plan." else "Saliste del plan.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: AuthSessionRequiredException) { account.expiredSession(); access("plan/${Uri.encode(plan.id)}") }
            catch (failure: Exception) {
                actionError = failure.message ?: "No se pudo completar el cambio."
                viewModel.openPlan(plan.id)
            }
            finally { actionBusy = false }
        }
    }
    LaunchedEffect(auth.loginEvent) {
        if (auth.loginEvent > 0 && auth.session != null && controller.currentDestination?.route == "acceso") {
            if (account.interestsComplete()) goAfterAccess()
            else controller.navigate("intereses") { popUpTo("acceso") { inclusive = true } }
        }
    }
    Box(Modifier.fillMaxSize()) {
        NavHost(controller, startDestination = "carga") {
            composable("carga") {
                SplashScreen()
                LaunchedEffect(auth.ready) {
                    if (auth.ready) controller.navigate(if (auth.session == null) "acceso" else if (account.interestsComplete()) "inicio" else "intereses") {
                        popUpTo("carga") { inclusive = true }
                    }
                }
            }
            composable("acceso") {
                AccessScreen(auth, account::mode, account::submit, account::resend, onExplore = {
                    if (account.interestsComplete()) goAfterAccess()
                    else controller.navigate("intereses") { popUpTo("acceso") { inclusive = true } }
                }, onGoogle = ::signInGoogle, onCancelGoogle = account::cancelGoogle, onRestore = account::retrySession)
            }
            composable("intereses") {
                InterestsScreen(account.interests()) { selected -> account.saveInterests(selected); goAfterAccess() }
            }
            composable("inicio") {
                HomeScreen(plans = state.plans, isLoading = state.isLoading,
                    isLoadingMore = state.isLoadingMore, errorMessage = state.errorMessage,
                    hasMore = state.nextKey != null, onRefresh = viewModel::refresh,
                    area = state.area, nowSeconds = now, onLocation = { showLocation = true },
                    onRetry = viewModel::retry, onLoadMore = viewModel::loadMore,
                    onOpenPlan = { controller.navigate("plan/${Uri.encode(it.id)}") { launchSingleTop = true } },
                    onCreate = ::create, onNavigate = ::navigate,
                    hasSavedGoogleLocation = state.savedGooglePlaceId != null,
                    userId = auth.session?.userId, interests = account.interests(),
                    requestedScope = requestedFeed, onScopeApplied = { requestedFeed = null })
            }
            composable("perfil") { entry ->
                val model: ProfileViewModel? = auth.session?.let { viewModel(entry, key = "profile-${it.userId}", factory = profileFactory(it.userId)) }
                val profileState = model?.state?.collectAsStateWithLifecycle()?.value
                LaunchedEffect(entry, auth.session?.userId, auth.loginEvent) { model?.load(preserveDraft = true) }
                LaunchedEffect(profileState?.requiresLogin) { if (profileState?.requiresLogin == true) {
                    model?.acknowledgeLogin(); account.expiredSession(); access("perfil")
                } }
                ProfileScreen(name = profileState?.profile?.name ?: auth.session?.name, email = auth.session?.email,
                    interests = account.interests(), area = state.area, hasSavedGoogleLocation = state.savedGooglePlaceId != null,
                    busy = auth.busy, error = auth.error.takeIf { auth.session != null },
                    onBack = { if (!controller.popBackStack()) navigate(BottomTab.HOME) },
                    onInterests = { pendingRoute = "perfil"; controller.navigate("intereses") { launchSingleTop = true } },
                    onLocation = { showLocation = true }, onFeed = { selected ->
                        requestedFeed = selected; navigate(BottomTab.HOME)
                    }, onLogin = { access("perfil") },
                    onLogout = { logoutRequested = true; account.logout() }, onNavigate = ::navigate, onCreate = ::create,
                    avatarUrl = profileState?.profile?.avatarUrl,
                    onEdit = if (auth.session != null) ({ controller.navigate("editar-perfil") { launchSingleTop = true } }) else null,
                    profileLoading = profileState?.loading == true, profileError = profileState?.error,
                    onRetryProfile = { model?.load(preserveDraft = true) }, interestsSyncing = auth.interestsSyncing,
                    interestsError = auth.interestsError, onRetryInterests = account::retryInterests,
                    verification = profileState?.profile?.verification,
                    activity = profileState?.activity, activityLoading = profileState?.activityLoading == true,
                    activityError = profileState?.activityError, onRetryActivity = { model?.retryActivity() },
                    confidence = profileState?.confidence, confidenceLoading = profileState?.confidenceLoading == true,
                    confidenceError = profileState?.confidenceError, onRetryConfidence = { model?.retryConfidence() })
            }
            composable("editar-perfil") {
                val session = auth.session
                if (session == null) {
                    LaunchedEffect(Unit) { access("editar-perfil") }
                } else {
                    val owner = remember(controller) { controller.getBackStackEntry("perfil") }
                    val model: ProfileViewModel = viewModel(owner, key = "profile-${session.userId}", factory = profileFactory(session.userId))
                    val editing by model.state.collectAsStateWithLifecycle()
                    LaunchedEffect(session.userId, auth.loginEvent) { model.load(preserveDraft = true) }
                    LaunchedEffect(editing.requiresLogin) { if (editing.requiresLogin) {
                        model.acknowledgeLogin(); account.expiredSession(); access("editar-perfil")
                    } }
                    LaunchedEffect(editing.savedEvent) { if (editing.savedEvent > 0) {
                        model.acknowledgeSaved(); controller.popBackStack(); message("Tu perfil se guardó correctamente.")
                    } }
                    EditProfileScreen(editing, model::name, model::select, model::removePhoto, model::error,
                        onSave = { model.save() }, onReload = { model.load() },
                        onBack = { model.discard(); controller.popBackStack() })
                }
            }
            composable("crear") { entry ->
                val editor: PlanEditorViewModel = viewModel(entry, factory = PlanEditorViewModel.Factory(null, RepositoryModule.getPlan, actions, photos))
                val editing by editor.state.collectAsStateWithLifecycle()
                LaunchedEffect(editing.requiresLogin) { if (editing.requiresLogin) { account.expiredSession(); editor.acknowledgeLogin(); access("crear") } }
                LaunchedEffect(editing.saved?.id) { editing.saved?.let {
                    viewModel.applyPlan(it)
                    controller.navigate("plan/${Uri.encode(it.id)}") { popUpTo("crear") { inclusive = true } }
                    message("Tu plan se publicó correctamente.")
                } }
                PlanEditorScreen(editing, false, state.area, editor::change, onSave = {
                    if (editing.draft.error() != null || auth.session != null) editor.save() else access("crear")
                }, onRetry = editor::load, onBack = { controller.popBackStack() }, onPhotoSelected = editor::selectPhoto,
                    onPhotoRemove = editor::removePhoto, onPhotoError = editor::photoError,
                    onPhotoRemoveAt = editor::removePhotoAt, onPhotoCover = editor::makeCover)
            }
            composable("editar/{planId}", arguments = listOf(navArgument("planId") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("planId")
                val editor: PlanEditorViewModel = viewModel(entry, factory = PlanEditorViewModel.Factory(id, RepositoryModule.getPlan, actions, photos))
                val editing by editor.state.collectAsStateWithLifecycle()
                LaunchedEffect(editing.requiresLogin) { if (editing.requiresLogin) { account.expiredSession(); editor.acknowledgeLogin(); access("editar/${Uri.encode(id)}") } }
                LaunchedEffect(editing.saved?.id) { editing.saved?.let {
                    viewModel.applyPlan(it); controller.popBackStack(); message("Cambios guardados.")
                } }
                PlanEditorScreen(editing, true, state.area, editor::change, editor::save, editor::load,
                    onBack = { controller.popBackStack() }, onPhotoSelected = editor::selectPhoto,
                    onPhotoRemove = editor::removePhoto, onPhotoError = editor::photoError,
                    onPhotoRemoveAt = editor::removePhotoAt, onPhotoCover = editor::makeCover)
            }
            composable("plan/{planId}", arguments = listOf(navArgument("planId") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("planId")
                LaunchedEffect(id) { if (id != null) viewModel.openPlan(id) }
                PlanDetailScreen(plan = detail.plan,
                    isLoading = detail.isLoading, error = detail.error, actionBusy = actionBusy, userId = auth.session?.userId,
                    onJoin = { detail.plan?.let { perform(it, "join") } },
                    onLeave = { detail.plan?.let { perform(it, "leave") } },
                    onCancel = { detail.plan?.let { perform(it, "cancel") } },
                    onEdit = { controller.navigate("editar/${Uri.encode(id)}") },
                    onRetry = { if (id != null) viewModel.openPlan(id) }, area = state.area, nowSeconds = now,
                    onLocation = { showLocation = true }, onBack = { controller.popBackStack() },
                    onCreate = ::create, onNavigate = ::navigate,
                    onDirections = { plan ->
                        val destination = plan.coordinates?.let { "${it.latitude},${it.longitude}" } ?: plan.meetingPlace.orEmpty()
                        val uri = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                            .appendQueryParameter("api", "1").appendQueryParameter("destination", destination).build()
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        catch (_: android.content.ActivityNotFoundException) { message("No hay una aplicación para abrir el mapa.") }
                    }, onShare = { plan ->
                        val text = buildString {
                            append("${plan.title}\n${plan.description.orEmpty()}\n")
                            plan.meetingPlace?.let { append("Encuentro: $it\n") }
                            plan.coordinates?.let { append("https://www.google.com/maps/search/?api=1&query=${it.latitude},${it.longitude}\n") }
                            append("PlanYa · ID del plan: ${plan.id}")
                        }
                        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Compartir plan")) }
                        catch (_: android.content.ActivityNotFoundException) { message("No hay una aplicación para compartir.") }
                    })
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 78.dp))
    }
    if (showLocation) LocationPicker(currentArea = state.area, onSelect = {
        viewModel.setArea(it)
        message("Ubicación actualizada.")
    }, onDismiss = { showLocation = false }, savedGooglePlaceId = state.savedGooglePlaceId)
    if (actionError != null) AlertDialog(onDismissRequest = { actionError = null }, title = { Text("No se pudo completar") },
        text = { Text(actionError!!) }, confirmButton = { TextButton(onClick = { actionError = null }) { Text("Entendido") } })
}

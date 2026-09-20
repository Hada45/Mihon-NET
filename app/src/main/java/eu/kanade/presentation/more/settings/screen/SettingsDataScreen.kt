package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.more.settings.screen.data.StorageInfo
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.export.LibraryExporter
import eu.kanade.tachiyomi.data.export.LibraryExporter.ExportOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Help
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StoragePreferences
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import eu.kanade.tachiyomi.data.ftp.FtpDownloadStorage
import eu.kanade.tachiyomi.data.smb.SmbDownloadStorage
import eu.kanade.tachiyomi.data.remote.StbDownloadClient
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsDataScreen : SearchableSettings {

    val restorePreferenceKeyString = MR.strings.label_backup
    const val HELP_URL = "https://mihon.app/docs/faq/storage"

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_data_storage

    @Composable
    override fun RowScope.AppBarAction() {
        val uriHandler = LocalUriHandler.current
        IconButton(onClick = { uriHandler.openUri(HELP_URL) }) {
            Icon(
                imageVector = MaterialSymbols.AutoMirroredRounded.Help,
                contentDescription = stringResource(MR.strings.tracking_guide),
            )
        }
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val backupPreferences = remember { context.appGraph.backupPreferences }
        val storagePreferences = remember { context.appGraph.storagePreferences }
        val downloadPreferences = remember { context.appGraph.downloadPreferences }
        val stbClient = remember { context.appGraph.stbDownloadClient }

        return listOf(
            getNetworkStorageGroup(
                storagePreferences = storagePreferences,
                downloadPreferences = downloadPreferences,
            ),
            getDownloadWorkerGroup(
                downloadPreferences = downloadPreferences,
                stbClient = stbClient,
            ),

            getBackupAndRestoreGroup(backupPreferences = backupPreferences),
            getDataGroup(),
            getExportGroup(),
        )
    }

    @Composable
    fun storageLocationPicker(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): ManagedActivityResultLauncher<Uri?, Uri?> {
        val context = LocalContext.current

        return rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                // For some reason InkBook devices do not implement the SAF properly. Persistable URI grants do not
                // work. However, simply retrieving the URI and using it works fine for these devices. Access is not
                // revoked after the app is closed or the device is restarted.
                // This also holds for some Samsung devices. Thus, we simply execute inside of a try-catch block and
                // ignore the exception if it is thrown.
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: SecurityException) {
                    logcat(LogPriority.ERROR, e)
                    context.toast(MR.strings.file_picker_uri_permission_unsupported)
                }

                UniFile.fromUri(context, uri)?.let {
                    storageDirPref.set(it.uri.toString())
                }
            }
        }
    }

    @Composable
    fun storageLocationText(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): String {
        val context = LocalContext.current
        val storageDir by storageDirPref.collectAsState()

        if (storageDir == storageDirPref.defaultValue()) {
            return stringResource(MR.strings.no_location_set)
        }

        return remember(storageDir) {
            val file = UniFile.fromUri(context, storageDir.toUri())
            file?.displayablePath
        } ?: stringResource(MR.strings.invalid_location, storageDir)
    }

    @Composable
    private fun getStorageLocationPref(
        storagePreferences: StoragePreferences,
        downloadPreferences: DownloadPreferences,
    ): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val pickStorageLocation = storageLocationPicker(storagePreferences.baseStorageDirectory)

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_storage_location_local_folder),
            subtitle = storageLocationText(storagePreferences.baseStorageDirectory),
            onClick = {
                try {
                    pickStorageLocation.launch(null)
                } catch (e: ActivityNotFoundException) {
                    context.toast(MR.strings.file_picker_error)
                }
            },
        )
    }

    @Composable
    private fun getNetworkStorageGroup(
        storagePreferences: StoragePreferences,
        downloadPreferences: DownloadPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val storageType by downloadPreferences.downloadStorageType.collectAsState()
        val ftpSelected by downloadPreferences.ftpDownloadLocation.collectAsState()
        val effectiveType = when {
            storageType == "smb" -> "smb"
            storageType == "ftp" || ftpSelected -> "ftp"
            else -> "local"
        }
        val isSmb = effectiveType == "smb"
        val isFtp = effectiveType == "ftp"

        val ftpHost by downloadPreferences.ftpHost.collectAsState()
        val ftpPort by downloadPreferences.ftpPort.collectAsState()
        val ftpPath by downloadPreferences.ftpPath.collectAsState()
        val smbHost by downloadPreferences.smbHost.collectAsState()
        val smbPort by downloadPreferences.smbPort.collectAsState()
        val smbShareName by downloadPreferences.smbShareName.collectAsState()
        val smbUsername by downloadPreferences.smbUsername.collectAsState()
        val smbDomain by downloadPreferences.smbDomain.collectAsState()
        val smbPath by downloadPreferences.smbPath.collectAsState()

        LaunchedEffect(smbShareName) {
            val trimmed = smbShareName.trim()
            val currentPath = downloadPreferences.smbPath.get().trim()
            if (trimmed.isNotBlank() && (currentPath.isBlank() || currentPath == "/")) {
                downloadPreferences.smbPath.set("/${trimmed.lowercase()}")
            }
        }

        var showPasswordDialog by rememberSaveable { mutableStateOf(false) }
        var passwordTarget by rememberSaveable { mutableStateOf("ftp") }
        var passwordValue by rememberSaveable { mutableStateOf("") }
        var testResult by remember { mutableStateOf<String?>(null) }

        if (showPasswordDialog) {
            AlertDialog(
                onDismissRequest = { showPasswordDialog = false },
                title = { Text(if (passwordTarget == "smb") stringResource(MR.strings.pref_smb_pass) else stringResource(MR.strings.pref_ftp_pass)) },
                text = {
                    OutlinedTextField(
                        value = passwordValue,
                        onValueChange = { passwordValue = it },
                        label = { Text(stringResource(MR.strings.pref_smb_pass)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (passwordTarget == "smb") {
                            downloadPreferences.smbPassword.set(passwordValue)
                        } else {
                            downloadPreferences.ftpPassword.set(passwordValue)
                        }
                        showPasswordDialog = false
                    }) { Text(stringResource(MR.strings.action_save)) }
                },
                dismissButton = { TextButton(onClick = { showPasswordDialog = false }) { Text(stringResource(MR.strings.action_cancel)) } },
            )
        }
        if (testResult != null) {
            AlertDialog(
                onDismissRequest = { testResult = null },
                title = { Text(stringResource(MR.strings.connection_test)) },
                text = { Text(testResult.orEmpty()) },
                confirmButton = { TextButton(onClick = { testResult = null }) { Text(stringResource(MR.strings.action_ok)) } },
            )
        }

        val isRemote = isSmb || isFtp
        val storageItems = listOfNotNull(
            Preference.PreferenceItem.ListPreference(
                preference = downloadPreferences.downloadStorageType,
                entries = mapOf(
                    "local" to stringResource(MR.strings.pref_download_location_local),
                    "smb" to stringResource(MR.strings.pref_download_location_smb),
                    "ftp" to stringResource(MR.strings.pref_download_location_ftp),
                ),
                title = stringResource(MR.strings.pref_storage_location),
                subtitle = when (effectiveType) {
                    "smb" -> stringResource(MR.strings.pref_download_location_smb)
                    "ftp" -> stringResource(MR.strings.pref_download_location_ftp)
                    else -> stringResource(MR.strings.pref_download_location_local)
                },
                onValueChanged = { newValue ->
                    downloadPreferences.ftpDownloadLocation.set(newValue == "ftp")
                    true
                },
            ),
            if (!isRemote) getStorageLocationPref(storagePreferences, downloadPreferences) else null,
            if (!isRemote) Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_storage_location_info)) else null,

            if (isSmb) Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_smb_settings)) else null,
            if (isSmb) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.smbHost,
                title = stringResource(MR.strings.pref_smb_host),
                subtitle = smbHost.ifBlank { stringResource(MR.strings.pref_smb_host_hint) },
            ) else null,
            if (isSmb) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.smbPort,
                title = stringResource(MR.strings.pref_smb_port),
                subtitle = smbPort,
                onValueChanged = { value -> value.toIntOrNull()?.let { it in 1..65535 } == true },
            ) else null,
            if (isSmb) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.smbShareName,
                title = stringResource(MR.strings.pref_smb_share),
                subtitle = smbShareName.ifBlank { stringResource(MR.strings.pref_smb_share_hint) },
                onValueChanged = { newValue ->
                    val trimmed = newValue.trim()
                    if (trimmed.isNotBlank()) {
                        val currentPath = downloadPreferences.smbPath.get().trim()
                        val oldShare = downloadPreferences.smbShareName.get().trim()
                        if (currentPath.isBlank() || currentPath == "/" || currentPath.equals("/$oldShare", ignoreCase = true)) {
                            downloadPreferences.smbPath.set("/${trimmed.lowercase()}")
                        }
                    }
                    true
                },
            ) else null,
            if (isSmb) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.smbUsername,
                title = stringResource(MR.strings.pref_smb_user),
                subtitle = smbUsername.ifBlank { stringResource(MR.strings.pref_smb_user_hint) },
            ) else null,
            if (isSmb) Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_smb_pass),
                subtitle = stringResource(MR.strings.pref_smb_pass_hint),
                onClick = {
                    passwordTarget = "smb"
                    passwordValue = downloadPreferences.smbPassword.get()
                    showPasswordDialog = true
                },
            ) else null,
            if (isSmb) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.smbDomain,
                title = stringResource(MR.strings.pref_smb_domain),
                subtitle = smbDomain.ifBlank { stringResource(MR.strings.pref_optional) },
            ) else null,
            if (isSmb) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.smbPath,
                title = stringResource(MR.strings.pref_smb_path),
                subtitle = smbPath,
            ) else null,
            if (isSmb) Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_test_smb),
                onClick = {
                    scope.launch {
                        testResult = runCatching {
                            SmbDownloadStorage.testConnection(SmbDownloadStorage.config(downloadPreferences))
                        }.fold(onSuccess = { it }, onFailure = { it.message ?: "Connection failed" })
                    }
                },
            ) else null,
            if (isFtp) Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_ftp_settings)) else null,
            if (isFtp) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.ftpHost,
                title = stringResource(MR.strings.pref_ftp_host),
                subtitle = ftpHost.ifBlank { stringResource(MR.strings.pref_ftp_host_hint) },
            ) else null,
            if (isFtp) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.ftpPort,
                title = stringResource(MR.strings.pref_ftp_port),
                subtitle = ftpPort,
                onValueChanged = { value -> value.toIntOrNull()?.let { it in 1..65535 } == true },
            ) else null,
            if (isFtp) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.ftpUsername,
                title = stringResource(MR.strings.pref_ftp_user),
                subtitle = downloadPreferences.ftpUsername.get().ifBlank { stringResource(MR.strings.pref_optional) },
            ) else null,
            if (isFtp) Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_ftp_pass),
                subtitle = stringResource(MR.strings.pref_ftp_pass_hint),
                onClick = {
                    passwordTarget = "ftp"
                    passwordValue = downloadPreferences.ftpPassword.get()
                    showPasswordDialog = true
                },
            ) else null,
            if (isFtp) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.ftpPath,
                title = stringResource(MR.strings.pref_ftp_path),
                subtitle = ftpPath,
            ) else null,
            if (isFtp) Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.ftpPassiveMode,
                title = stringResource(MR.strings.pref_ftp_pasv),
            ) else null,
            if (isFtp) Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_test_ftp),
                onClick = {
                    scope.launch {
                        testResult = runCatching {
                            FtpDownloadStorage.testConnection(FtpDownloadStorage.config(downloadPreferences))
                        }.fold(onSuccess = { it }, onFailure = { it.message ?: "Connection failed" })
                    }
                },
            ) else null,
        )

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_storage_location),
            preferenceItems = storageItems,
        )
    }

    @Composable
    private fun getDownloadWorkerGroup(
        downloadPreferences: DownloadPreferences,
        stbClient: StbDownloadClient,
    ): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val downloadWorkerEnabled by downloadPreferences.downloadWorkerEnabled.collectAsState()
        val stbHost by downloadPreferences.stbWorkerHost.collectAsState()
        val stbPort by downloadPreferences.stbWorkerPort.collectAsState()

        var testResult by remember { mutableStateOf<String?>(null) }
        if (testResult != null) {
            AlertDialog(
                onDismissRequest = { testResult = null },
                title = { Text(stringResource(MR.strings.connection_test)) },
                text = { Text(testResult.orEmpty()) },
                confirmButton = { TextButton(onClick = { testResult = null }) { Text(stringResource(MR.strings.action_ok)) } },
            )
        }

        val workerItems = listOfNotNull(
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.downloadWorkerEnabled,
                title = stringResource(MR.strings.pref_download_worker_enable),
                subtitle = stringResource(MR.strings.pref_download_worker_summary),
            ),
            if (downloadWorkerEnabled) Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.stbWorkerSaveCbz,
                title = stringResource(MR.strings.pref_worker_save_cbz),
                subtitle = stringResource(MR.strings.pref_worker_save_cbz_summary),
            ) else null,
            if (downloadWorkerEnabled) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.stbWorkerHost,
                title = stringResource(MR.strings.pref_worker_host),
                subtitle = stbHost.ifBlank { stringResource(MR.strings.pref_worker_host_hint) },
            ) else null,
            if (downloadWorkerEnabled) Preference.PreferenceItem.EditTextPreference(
                preference = downloadPreferences.stbWorkerPort,
                title = stringResource(MR.strings.pref_worker_port),
                subtitle = stbPort,
                onValueChanged = { value -> value.toIntOrNull()?.let { it in 1..65535 } == true },
            ) else null,
            if (downloadWorkerEnabled) {
                val stbStoragePath by downloadPreferences.stbWorkerStoragePath.collectAsState()
                Preference.PreferenceItem.EditTextPreference(
                    preference = downloadPreferences.stbWorkerStoragePath,
                    title = stringResource(MR.strings.pref_worker_storage_path),
                    subtitle = stbStoragePath.ifBlank { stringResource(MR.strings.pref_worker_storage_path_hint) },
                )
            } else null,
            if (downloadWorkerEnabled) Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_worker_actions_group)) else null,
            if (downloadWorkerEnabled) Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_test_worker),
                subtitle = stringResource(MR.strings.pref_test_worker_summary),
                onClick = {
                    scope.launch {
                        testResult = runCatching { stbClient.testConnection() }
                            .fold(onSuccess = { it }, onFailure = { it.message ?: "Connection failed" })
                    }
                },
            ) else null,
            if (downloadWorkerEnabled) Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_worker_dashboard),
                subtitle = stringResource(MR.strings.pref_worker_dashboard_summary),
                onClick = {
                    scope.launch {
                        testResult = runCatching { stbClient.dashboard() }
                            .fold(onSuccess = { it }, onFailure = { it.message ?: "Dashboard unavailable" })
                    }
                },
            ) else null,
            if (downloadWorkerEnabled) Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_worker_pause_all),
                onClick = { scope.launch { testResult = runCatching { stbClient.controlAll("pause") }.getOrElse { it.message ?: "Action failed" } } },
            ) else null,
            if (downloadWorkerEnabled) Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_worker_resume_all),
                onClick = { scope.launch { testResult = runCatching { stbClient.controlAll("resume") }.getOrElse { it.message ?: "Action failed" } } },
            ) else null,
            if (downloadWorkerEnabled) Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_worker_retry_all),
                onClick = { scope.launch { testResult = runCatching { stbClient.controlAll("retry") }.getOrElse { it.message ?: "Action failed" } } },
            ) else null,
            if (downloadWorkerEnabled) Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_worker_cancel_all),
                onClick = { scope.launch { testResult = runCatching { stbClient.controlAll("cancel") }.getOrElse { it.message ?: "Action failed" } } },
            ) else null,
        )

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_download_worker),
            preferenceItems = workerItems,
        )
    }

    @Composable
    private fun getBackupAndRestoreGroup(backupPreferences: BackupPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp.collectAsState()

        val chooseBackup = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) {
            if (it == null) {
                context.toast(MR.strings.file_null_uri_error)
                return@rememberLauncherForActivityResult
            }

            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                logcat(LogPriority.ERROR, e)
            }

            navigator.push(RestoreBackupScreen(it.toString()))
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_backup),
            preferenceItems = listOf(
                // Manual actions
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(restorePreferenceKeyString),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            MultiChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(intrinsicSize = IntrinsicSize.Min)
                                    .padding(horizontal = PrefsHorizontalPadding),
                            ) {
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = { navigator.push(CreateBackupScreen()) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_create_backup))
                                }
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = {
                                        if (!BackupRestoreJob.isRunning(context.workManager)) {
                                            if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                                                context.toast(MR.strings.restore_miui_warning)
                                            }
                                            chooseBackup.launch(arrayOf("*/*"))
                                        } else {
                                            context.toast(MR.strings.restore_in_progress)
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_restore_backup))
                                }
                            }
                        },
                    )
                },

                // Automatic backups
                Preference.PreferenceItem.ListPreference(
                    preference = backupPreferences.backupInterval,
                    entries = mapOf(
                        0 to stringResource(MR.strings.off),
                        6 to stringResource(MR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_backup_interval),
                    onValueChanged = {
                        BackupCreateJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.backup_info) + "\n\n" +
                        stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)),
                ),
            ),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val libraryPreferences = remember { context.appGraph.libraryPreferences }

        val chapterCache = remember { context.appGraph.chapterCache }
        var cacheReadableSizeSema by remember { mutableIntStateOf(0) }
        val cacheReadableSize = remember(cacheReadableSizeSema) { chapterCache.readableSize }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_storage_usage),
            preferenceItems = listOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_storage_usage),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            StorageInfo(
                                modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                            )
                        },
                    )
                },

                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_chapter_cache),
                    subtitle = stringResource(MR.strings.used_cache, cacheReadableSize),
                    onClick = {
                        scope.launchNonCancellable {
                            try {
                                val deletedFiles = chapterCache.clear()
                                withUIContext {
                                    context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                    cacheReadableSizeSema++
                                }
                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e)
                                withUIContext { context.toast(MR.strings.cache_delete_error) }
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.autoClearChapterCache,
                    title = stringResource(MR.strings.pref_auto_clear_chapter_cache),
                ),
            ),
        )
    }

    @Composable
    private fun getExportGroup(): Preference.PreferenceGroup {
        var showDialog by remember { mutableStateOf(false) }
        var exportOptions by remember {
            mutableStateOf(
                ExportOptions(
                    includeTitle = true,
                    includeAuthor = true,
                    includeArtist = true,
                ),
            )
        }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val getFavorites = remember { context.appGraph.getFavorites }
        var favorites by remember { mutableStateOf<List<Manga>>(emptyList()) }
        LaunchedEffect(Unit) {
            favorites = getFavorites.await()
        }

        val saveFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            uri?.let {
                scope.launch {
                    LibraryExporter.exportToCsv(
                        context = context,
                        uri = it,
                        favorites = favorites,
                        options = exportOptions,
                        onExportComplete = {
                            scope.launch(Dispatchers.Main) {
                                context.toast(MR.strings.library_exported)
                            }
                        },
                    )
                }
            }
        }

        if (showDialog) {
            ColumnSelectionDialog(
                options = exportOptions,
                onConfirm = { options ->
                    exportOptions = options
                    saveFileLauncher.launch("mihon_library.csv")
                },
                onDismissRequest = { showDialog = false },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.export),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.library_list),
                    onClick = { showDialog = true },
                ),
            ),
        )
    }

    @Composable
    private fun ColumnSelectionDialog(
        options: ExportOptions,
        onConfirm: (ExportOptions) -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        var titleSelected by remember { mutableStateOf(options.includeTitle) }
        var authorSelected by remember { mutableStateOf(options.includeAuthor) }
        var artistSelected by remember { mutableStateOf(options.includeArtist) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
            },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = titleSelected,
                            onCheckedChange = { checked ->
                                titleSelected = checked
                                if (!checked) {
                                    authorSelected = false
                                    artistSelected = false
                                }
                            },
                        )
                        Text(text = stringResource(MR.strings.title))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = authorSelected,
                            onCheckedChange = { authorSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.author))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = artistSelected,
                            onCheckedChange = { artistSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.artist))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm(
                            ExportOptions(
                                includeTitle = titleSelected,
                                includeAuthor = authorSelected,
                                includeArtist = artistSelected,
                            ),
                        )
                        onDismissRequest()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

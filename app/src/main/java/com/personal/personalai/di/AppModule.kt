package com.personal.personalai.di

import android.content.Context
import androidx.datastore.core.DataStore
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.LocationServices
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.work.WorkManager
import com.personal.personalai.data.local.MIGRATION_1_2
import com.personal.personalai.data.local.MIGRATION_2_3
import com.personal.personalai.data.local.MIGRATION_3_4
import com.personal.personalai.data.local.MIGRATION_4_5
import com.personal.personalai.data.local.MIGRATION_5_6
import com.personal.personalai.data.local.MIGRATION_6_7
import com.personal.personalai.data.local.MIGRATION_7_8
import com.personal.personalai.data.local.MIGRATION_8_9
import com.personal.personalai.data.local.PersonalAIDatabase
import com.personal.personalai.data.local.dao.ApiUsageLogDao
import com.personal.personalai.data.local.dao.CandidateMemoryDao
import com.personal.personalai.data.local.dao.ConversationDao
import com.personal.personalai.data.local.dao.GeofenceTaskDao
import com.personal.personalai.data.local.dao.MemoryDao
import com.personal.personalai.data.local.dao.MessageDao
import com.personal.personalai.data.local.dao.ScheduledTaskDao
import com.personal.personalai.data.audio.AudioRecorderImpl
import com.personal.personalai.data.datasource.ai.LocalLlmDataSource
import com.personal.personalai.data.repository.AiRepositoryImpl
import com.personal.personalai.data.repository.ApiUsageRepositoryImpl
import com.personal.personalai.data.repository.CandidateMemoryRepositoryImpl
import com.personal.personalai.data.repository.ConversationRepositoryImpl
import com.personal.personalai.data.router.LumioAiRouterImpl
import com.personal.personalai.localllm.engine.LiteRtLlmEngine
import com.personal.personalai.data.repository.ChatRepositoryImpl
import com.personal.personalai.data.repository.GeofenceTaskRepositoryImpl
import com.personal.personalai.data.repository.MemoryRepositoryImpl
import com.personal.personalai.data.repository.TaskRepositoryImpl
import com.personal.personalai.data.tools.ToolRegistryImpl
import com.personal.personalai.data.tools.android.AddCalendarEventTool
import com.personal.personalai.data.tools.android.DialPhoneTool
import com.personal.personalai.data.tools.android.GetBatteryLevelTool
import com.personal.personalai.data.tools.android.GeocodeAddressTool
import com.personal.personalai.data.tools.android.GetClipboardTool
import com.personal.personalai.data.tools.android.GetInstalledAppsTool
import com.personal.personalai.data.tools.android.GetLocationTool
import com.personal.personalai.data.tools.android.OpenAppTool
import com.personal.personalai.data.tools.android.OpenUrlTool
import com.personal.personalai.data.tools.android.ReadContactsTool
import com.personal.personalai.data.tools.android.SendNotificationTool
import com.personal.personalai.data.tools.android.SendMessageTool
import com.personal.personalai.data.tools.android.SendSmsTool
import com.personal.personalai.data.tools.android.SetAlarmTool
import com.personal.personalai.data.tools.interaction.AskUserTool
import com.personal.personalai.data.tools.management.ForgetAllMemoriesTool
import com.personal.personalai.data.tools.management.ForgetMemoryTool
import com.personal.personalai.data.tools.management.SaveMemoryTool
import com.personal.personalai.data.tools.management.ScheduleTaskTool
import com.personal.personalai.data.tools.management.SetLocationTaskTool
import com.personal.personalai.data.tools.files.DeleteFileTool
import com.personal.personalai.data.tools.files.ListFilesTool
import com.personal.personalai.data.tools.files.ReadFileTool
import com.personal.personalai.data.tools.files.ShareFileTool
import com.personal.personalai.data.tools.files.WriteFileTool
import com.personal.personalai.data.tools.web.WebSearchTool
import com.personal.personalai.domain.audio.AudioRecorder
import com.personal.personalai.domain.repository.AiRepository
import com.personal.personalai.domain.repository.ApiUsageRepository
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.domain.repository.GeofenceTaskRepository
import com.personal.personalai.domain.repository.MemoryRepository
import com.personal.personalai.domain.repository.TaskRepository
import com.personal.personalai.domain.router.LumioAiRouter
import com.personal.personalai.domain.tools.AgentTool
import com.personal.personalai.domain.tools.ToolRegistry
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PersonalAIDatabase =
        Room.databaseBuilder(context, PersonalAIDatabase::class.java, "personal_ai_db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
            )
            .build()

    @Provides
    fun provideConversationDao(db: PersonalAIDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: PersonalAIDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideScheduledTaskDao(db: PersonalAIDatabase): ScheduledTaskDao = db.scheduledTaskDao()

    @Provides
    fun provideMemoryDao(db: PersonalAIDatabase): MemoryDao = db.memoryDao()

    @Provides
    fun provideGeofenceTaskDao(db: PersonalAIDatabase): GeofenceTaskDao = db.geofenceTaskDao()

    @Provides
    fun provideApiUsageLogDao(db: PersonalAIDatabase): ApiUsageLogDao = db.apiUsageLogDao()

    @Provides
    fun provideCandidateMemoryDao(db: PersonalAIDatabase): CandidateMemoryDao = db.candidateMemoryDao()

    @Provides
    @Singleton
    fun provideGeofencingClient(@ApplicationContext context: Context): GeofencingClient =
        LocationServices.getGeofencingClient(context)

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    @Provides
    @Singleton
    fun provideLiteRtLlmEngine(@ApplicationContext context: Context): LiteRtLlmEngine =
        LiteRtLlmEngine(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAudioRecorder(impl: AudioRecorderImpl): AudioRecorder

    @Binds
    @Singleton
    abstract fun bindLumioAiRouter(impl: LumioAiRouterImpl): LumioAiRouter

    @Binds
    @Singleton
    abstract fun bindAiRepository(impl: AiRepositoryImpl): AiRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: MemoryRepositoryImpl): MemoryRepository

    @Binds
    @Singleton
    abstract fun bindCandidateMemoryRepository(impl: CandidateMemoryRepositoryImpl): CandidateMemoryRepository

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): com.personal.personalai.domain.repository.ConversationRepository

    @Binds
    @Singleton
    abstract fun bindGeofenceTaskRepository(impl: GeofenceTaskRepositoryImpl): GeofenceTaskRepository

    @Binds
    @Singleton
    abstract fun bindApiUsageRepository(impl: ApiUsageRepositoryImpl): ApiUsageRepository
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ToolModule {

    /** Declares the Set<AgentTool> multibinding (required even when elements are always provided). */
    @Multibinds
    abstract fun toolSet(): Set<AgentTool>

    @Binds
    @Singleton
    abstract fun bindToolRegistry(impl: ToolRegistryImpl): ToolRegistry

    // ── Management tools ─────────────────────────────────────────────────────

    @Binds @IntoSet
    abstract fun bindScheduleTaskTool(impl: ScheduleTaskTool): AgentTool

    @Binds @IntoSet
    abstract fun bindSetLocationTaskTool(impl: SetLocationTaskTool): AgentTool

    @Binds @IntoSet
    abstract fun bindSaveMemoryTool(impl: SaveMemoryTool): AgentTool

    @Binds @IntoSet
    abstract fun bindForgetMemoryTool(impl: ForgetMemoryTool): AgentTool

    @Binds @IntoSet
    abstract fun bindForgetAllMemoriesTool(impl: ForgetAllMemoriesTool): AgentTool

    // ── Android tools ────────────────────────────────────────────────────────

    @Binds @IntoSet
    abstract fun bindOpenAppTool(impl: OpenAppTool): AgentTool

    @Binds @IntoSet
    abstract fun bindGeocodeAddressTool(impl: GeocodeAddressTool): AgentTool

    @Binds @IntoSet
    abstract fun bindOpenUrlTool(impl: OpenUrlTool): AgentTool

    @Binds @IntoSet
    abstract fun bindGetInstalledAppsTool(impl: GetInstalledAppsTool): AgentTool

    @Binds @IntoSet
    abstract fun bindReadContactsTool(impl: ReadContactsTool): AgentTool

    @Binds @IntoSet
    abstract fun bindGetClipboardTool(impl: GetClipboardTool): AgentTool

    // ── Interaction tools ─────────────────────────────────────────────────────

    @Binds @IntoSet
    abstract fun bindAskUserTool(impl: AskUserTool): AgentTool

    // ── Communication tools ───────────────────────────────────────────────────

    @Binds @IntoSet
    abstract fun bindSendSmsTool(impl: SendSmsTool): AgentTool

    @Binds @IntoSet
    abstract fun bindSendMessageTool(impl: SendMessageTool): AgentTool

    @Binds @IntoSet
    abstract fun bindDialPhoneTool(impl: DialPhoneTool): AgentTool

    // ── Device tools ──────────────────────────────────────────────────────────

    @Binds @IntoSet
    abstract fun bindSetAlarmTool(impl: SetAlarmTool): AgentTool

    @Binds @IntoSet
    abstract fun bindGetBatteryLevelTool(impl: GetBatteryLevelTool): AgentTool

    @Binds @IntoSet
    abstract fun bindSendNotificationTool(impl: SendNotificationTool): AgentTool

    @Binds @IntoSet
    abstract fun bindGetLocationTool(impl: GetLocationTool): AgentTool

    @Binds @IntoSet
    abstract fun bindAddCalendarEventTool(impl: AddCalendarEventTool): AgentTool

    // ── Web tools ──────────────────────────────────────────────────────────────

    @Binds @IntoSet
    abstract fun bindWebSearchTool(impl: WebSearchTool): AgentTool

    // ── File tools ─────────────────────────────────────────────────────────────

    @Binds @IntoSet
    abstract fun bindWriteFileTool(impl: WriteFileTool): AgentTool

    @Binds @IntoSet
    abstract fun bindReadFileTool(impl: ReadFileTool): AgentTool

    @Binds @IntoSet
    abstract fun bindListFilesTool(impl: ListFilesTool): AgentTool

    @Binds @IntoSet
    abstract fun bindDeleteFileTool(impl: DeleteFileTool): AgentTool

    @Binds @IntoSet
    abstract fun bindShareFileTool(impl: ShareFileTool): AgentTool
}

package com.tmapp.android

import android.content.Context
import androidx.room.Room
import com.tmapp.data.AppDatabase
import com.tmapp.data.SavedDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun context(@ApplicationContext context: Context): Context = context

    @Provides @Singleton
    fun database(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "tm.db").build()

    @Provides
    fun savedDao(db: AppDatabase): SavedDao = db.saved()

    @Provides @Named("apiBaseUrl")
    fun apiBaseUrl(): String = BuildConfig.API_BASE_URL
}
